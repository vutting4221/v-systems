package com.wavesplatform.it

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.it.api.NodeApi.{AssetBalance, LevelResponse, MatcherStatusResponse, OrderBookResponse, Transaction}
import com.wavesplatform.matcher.api.CancelOrderRequest
import vsys.blockchain.state.ByteStr
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import vsys.account.{PrivateKeyAccount, PublicKeyAccount}
import scorex.crypto.encode.Base58
import vsys.blockchain.transaction.assets.exchange.{AssetPair, Order, OrderType}
import vsys.utils.NTP

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

class MatcherTestSuite extends FreeSpec with Matchers with BeforeAndAfterAll {

  import MatcherTestSuite._

  private val docker = new Docker()

  private val nodes = Configs.map(docker.startNode)

  private val matcherNode = nodes.head
  private val aliceNode = nodes(1)
  private val bobNode = nodes(2)

  private var matcherBalance = (0L, 0L)
  private var aliceBalance = (0L, 0L)
  private var bobBalance = (0L, 0L)

  private var aliceSell1 = ""
  private var bobBuy1 = ""

  private var aliceAsset: String = ""
  private var aliceWavesPair: AssetPair = AssetPair(None, None)

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    // Store initial balances of participants
    matcherBalance = getBalance(matcherNode)
    aliceBalance = getBalance(aliceNode)
    bobBalance = getBalance(bobNode)

    // Alice issues new asset
    aliceAsset = issueAsset(aliceNode, "AliceCoin", AssetQuantity)
    aliceWavesPair = AssetPair(ByteStr.decodeBase58(aliceAsset).toOption, None)

    // Wait for balance on Alice's account
    waitForAssetBalance(aliceNode, aliceAsset, AssetQuantity)
    waitForAssetBalance(matcherNode, aliceAsset, 0)
    waitForAssetBalance(bobNode, aliceAsset, 0)

    // Alice spent 1 Wave to issue the asset
    aliceBalance = getBalance(aliceNode)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    docker.close()
  }

  "matcher should respond with Public key" in {
    Await.result(matcherNode.matcherGet("/matcher"), 1.minute)
      .getResponseBody.stripPrefix("\"").stripSuffix("\"") shouldBe matcherNode.publicKey
  }

  "sell order could be placed" in {
    // Alice places sell order
    val (id, status) = matcherPlaceOrder(
      prepareOrder(aliceNode, aliceWavesPair, OrderType.SELL, 2 * Waves * Order.PriceConstant, 500))
    status shouldBe "OrderAccepted"
    aliceSell1 = id
    // Alice checks that the order in order book
    matcherCheckOrderStatus(id) shouldBe "Accepted"

    // Alice check that order is correct
    val orders = matcherGetOrderBook()
    orders.asks.head.amount shouldBe 500
    orders.asks.head.price shouldBe 2 * Waves * Order.PriceConstant
  }

  "and should match with buy order" in {
    // Bob places a buy order
    val (id, status) = matcherPlaceOrder(
      prepareOrder(bobNode, aliceWavesPair, OrderType.BUY, 2 * Waves * Order.PriceConstant, 200))
    bobBuy1 = id
    status shouldBe "OrderAccepted"

    waitForOrderStatus(aliceAsset, aliceSell1, "PartiallyFilled")

    // Bob waits for order to fill
    waitForOrderStatus(aliceAsset, bobBuy1, "Filled")

    // Bob checks that asset on his balance
    waitForAssetBalance(bobNode, aliceAsset, 200)

    // Alice checks that part of her order still in the order book
    val orders = matcherGetOrderBook()
    orders.asks.head.amount shouldBe 300
    orders.asks.head.price shouldBe 2 * Waves * Order.PriceConstant

    // Alice checks that she sold some assets
    waitForAssetBalance(aliceNode, aliceAsset, 800)

    // Bob checks that he spent some Waves
    val updatedBobBalance = getBalance(bobNode)
    val bobMined = getGeneratedFee(bobNode, bobBalance._2)
    updatedBobBalance._1 shouldBe (bobBalance._1 + bobMined._1 - 2 * Waves * 200 - MatcherFee)
    bobBalance = updatedBobBalance

    // Alice checks that she received some Waves
    val updatedAliceBalance = getBalance(aliceNode)
    val aliceMined = getGeneratedFee(aliceNode, aliceBalance._2)
    updatedAliceBalance._1 shouldBe (aliceBalance._1 + aliceMined._1 + 2 * Waves * 200 - MatcherFee * 200 / 500)
    aliceBalance = updatedAliceBalance

    // Matcher checks that it earn fees
    val updatedMatcherBalance = getBalance(matcherNode)
    val matcherMined = getGeneratedFee(matcherNode, matcherBalance._2)
    updatedMatcherBalance._1 shouldBe (matcherBalance._1 + matcherMined._1 + MatcherFee + MatcherFee * 200 / 500 - TransactionFee)
    matcherBalance = updatedMatcherBalance
  }

  "submitting sell orders should check availability of asset" in {
    // Bob trying to place order on more assets than he has - order rejected
    val badOrder = prepareOrder(bobNode, aliceWavesPair, OrderType.SELL, 19 * Waves / 10 * Order.PriceConstant, 300)
    val error = matcherExpectOrderPlacementRejected(badOrder, 400, "OrderRejected")
    error should be(true)

    // Bob places order on available amount of assets - order accepted
    val goodOrder = prepareOrder(bobNode, aliceWavesPair, OrderType.SELL, 19 * Waves / 10 * Order.PriceConstant, 150)
    val (_, status) = matcherPlaceOrder(goodOrder)
    status should be("OrderAccepted")

    // Bob checks that the order in the order book
    val orders = matcherGetOrderBook()
    orders.asks should contain(LevelResponse(19 * Waves / 10 * Order.PriceConstant, 150))
  }

  "buy order should match on few price levels" in {
    // Alice places a buy order
    val order = prepareOrder(aliceNode, aliceWavesPair, OrderType.BUY, 21 * Waves / 10 * Order.PriceConstant, 350)
    val (id, status) = matcherPlaceOrder(order)
    status should be("OrderAccepted")

    // Where were 2 sells that should fulfill placed order
    waitForOrderStatus(aliceAsset, id, "Filled")

    // Check balances
    waitForAssetBalance(aliceNode, aliceAsset, 950)
    waitForAssetBalance(bobNode, aliceAsset, 50)

    val updatedMatcherBalance = getBalance(matcherNode)
    val matcherMined = getGeneratedFee(matcherNode, matcherBalance._2 + 1)
    updatedMatcherBalance._1 should be(matcherBalance._1 + matcherMined._1 - 2 * TransactionFee + MatcherFee + MatcherFee * 150 / 350 + MatcherFee * 200 / 350 + MatcherFee * 200 / 500)
    matcherBalance = updatedMatcherBalance

    val updatedBobBalance = getBalance(bobNode)
    val bobMined = getGeneratedFee(bobNode, bobBalance._2 + 1)
    updatedBobBalance._1 should be(bobBalance._1 + bobMined._1 - MatcherFee + 150 * 19 * Waves / 10)
    bobBalance = updatedBobBalance

    val updatedAliceBalance = getBalance(aliceNode)
    val aliceMined = getGeneratedFee(aliceNode, aliceBalance._2 + 1)
    updatedAliceBalance._1 should be(aliceBalance._1 + aliceMined._1 - MatcherFee * 200 / 350 - MatcherFee * 150 / 350 - MatcherFee * 200 / 500 - 19 * Waves / 10 * 150)
    aliceBalance = updatedAliceBalance
  }

  "order could be canceled and resubmitted again" in {
    // Alice cancels the very first order (100 left)
    val status1 = matcherCancelOrder(aliceNode, aliceWavesPair, aliceSell1)
    status1 should be("OrderCanceled")

    // Alice checks that the order book is empty
    val orders1 = matcherGetOrderBook()
    orders1.asks.size should be(0)
    orders1.bids.size should be(0)

    // Alice places a new sell order on 100
    val order = prepareOrder(aliceNode, aliceWavesPair, OrderType.SELL, 2 * Waves * Order.PriceConstant, 100)
    val (id, status2) = matcherPlaceOrder(order)
    status2 should be ("OrderAccepted")

    // Alice checks that the order is in the order book
    val orders2 = matcherGetOrderBook()
    orders2.asks should contain(LevelResponse(20 * Waves / 10 * Order.PriceConstant, 100))
  }

  "buy order should execute all open orders and put remaining in order book" in {
    // Bob places buy order on amount bigger then left in sell orders
    val order = prepareOrder(bobNode, aliceWavesPair,OrderType.BUY, 2 * Waves * Order.PriceConstant, 130)
    val (id, status) = matcherPlaceOrder(order)
    status should be("OrderAccepted")

    // Check that the order is partially filled
    waitForOrderStatus(aliceAsset, id, "PartiallyFilled")

    // Check that remaining part of the order is in the order book
    val orders = matcherGetOrderBook()
    orders.bids should contain(LevelResponse(2 * Waves * Order.PriceConstant, 30))

    // Check balances
    waitForAssetBalance(aliceNode, aliceAsset, 850)
    waitForAssetBalance(bobNode, aliceAsset, 150)

    val updatedMatcherBalance = getBalance(matcherNode)
    val matcherMined = getGeneratedFee(matcherNode, matcherBalance._2 + 1)
    updatedMatcherBalance._1 should be(matcherBalance._1 + matcherMined._1 - TransactionFee + MatcherFee + MatcherFee * 100 / 130)
    matcherBalance = updatedMatcherBalance

    val updatedBobBalance = getBalance(bobNode)
    val bobMined = getGeneratedFee(bobNode, bobBalance._2 + 1)
    updatedBobBalance._1 should be(bobBalance._1 + bobMined._1 - MatcherFee * 100 / 130 - 100 * 2 * Waves)
    bobBalance = updatedBobBalance

    val updatedAliceBalance = getBalance(aliceNode)
    val aliceMined = getGeneratedFee(aliceNode, aliceBalance._2 + 1)
    updatedAliceBalance._1 should be(aliceBalance._1 + aliceMined._1 - MatcherFee + 2 * Waves * 100)
    aliceBalance = updatedAliceBalance
  }

  "request order book for blacklisted pair" in {
    val f = matcherNode.matcherGetStatusCode(s"/matcher/orderbook/$ForbiddenAssetId/WAVES", 404)

    val result = Await.result(f, 1.minute)
    result.message shouldBe s"Invalid Asset ID: $ForbiddenAssetId"
  }

  "should consider UTX pool when checking the balance" in {
    // Bob issues new asset
    val bobAssetQuantity = 10000
    val bobAssetName = "BobCoin"
    val bobAsset = issueAsset(bobNode, bobAssetName, bobAssetQuantity)
    val bobAssetId = ByteStr.decodeBase58(bobAsset).get
    waitForAssetBalance(bobNode, bobAsset, bobAssetQuantity)
    waitForAssetBalance(matcherNode, bobAsset, 0)
    waitForAssetBalance(aliceNode, bobAsset, 0)

    // Bob wants to sell all own assets for 1 Wave
    val bobWavesPair = AssetPair(
      amountAsset = Some(bobAssetId),
      priceAsset = None
    )
    def bobOrder = prepareOrder(bobNode, bobWavesPair, OrderType.SELL, 1 * Waves * Order.PriceConstant, bobAssetQuantity)
    matcherPlaceOrder(bobOrder)

    // Alice wants to buy all Bob's assets for 1 Wave
    val (buyId, _) = matcherPlaceOrder(prepareOrder(aliceNode, bobWavesPair, OrderType.BUY, 1 * Waves * Order.PriceConstant, bobAssetQuantity))
    waitForOrderStatus(aliceAsset, buyId, "Filled")

    // Bob tries to do the same operation, but at now he have no assets
    matcherExpectOrderPlacementRejected(
      order = bobOrder,
      expectedStatusCode = 400,
      expectedStatus = "OrderRejected"
    )
  }

  private def issueAsset(node: Node, name: String, amount: Long): String = {
    val description = "asset for integration tests of matcher"
    val fee = 100000000L
    val futureIssueTransaction: Future[Transaction] = for {
      a <- node.issueAsset(node.address, name, description, amount, 0, fee, reissuable = false)
    } yield a

    val issueTransaction = Await.result(futureIssueTransaction, 1.minute)

    issueTransaction.id
  }

  private def waitForAssetBalance(node: Node, asset: String, expectedBalance: Long): Unit =
    Await.result(
      node.waitFor[AssetBalance](node.assetBalance(node.address, asset), _.balance >= expectedBalance, 5.seconds),
      3.minute
    )

  private def getBalance(node: Node): (Long, Long) = {
    val initialHeight = Await.result(node.height, 1.minute)
    Await.result(node.waitForHeight(initialHeight + 1), 1.minute)

    val balance = Await.result(node.balance(node.address), 1.minute).balance
    val height = Await.result(node.height, 1.minute)

    (balance, height)
  }

  private def prepareOrder(node: Node, pair: AssetPair, orderType: OrderType, price: Long, amount: Long): Order = {
    val creationTime = NTP.correctedTime()
    val timeToLive = creationTime + Order.MaxLiveTime - 1000

    val privateKey = PrivateKeyAccount(Base58.decode(node.accountSeed).get)
    val matcherPublicKey = PublicKeyAccount(Base58.decode(matcherNode.publicKey).get)

    Order(privateKey, matcherPublicKey, pair, orderType, price, amount, creationTime, timeToLive, MatcherFee)
  }

  private def matcherPlaceOrder(order: Order): (String, String) = {
    val futureResult = matcherNode.placeOrder(order)

    val result = Await.result(futureResult, 1.minute)

    (result.message.id, result.status)
  }

  private def matcherExpectOrderPlacementRejected(order: Order, expectedStatusCode: Int, expectedStatus: String): Boolean = {
    val futureResult = matcherNode.expectIncorrectOrderPlacement(order, expectedStatusCode, expectedStatus)

    Await.result(futureResult, 1.minute)
  }

  def matcherCheckOrderStatus(id: String): String = {
    val futureResult = matcherNode.getOrderStatus(aliceAsset, id)

    val response = Await.result(futureResult, 1.minute)

    response.status
  }

  def waitForOrderStatus(asset: String, orderId: String, expectedStatus: String): Unit = Await.result(
    matcherNode.waitFor[MatcherStatusResponse](matcherNode.getOrderStatus(asset, orderId), _.status == expectedStatus, 5.seconds),
    1.minute
  )

  def matcherGetOrderBook(): OrderBookResponse = {
    val futureResult = matcherNode.getOrderBook(aliceAsset)

    val result = Await.result(futureResult, 1.minute)

    result
  }

  private def getGeneratedFee(node: Node, prevHeight: Long): (Long, Long) = {
    val currentHeight = Await.result(node.height, 1.minute)
    val fee = Await.result(node.getGeneratedBlocks(node.address, prevHeight, currentHeight), 1.minute).map(_.fee).sum

    (fee, currentHeight)
  }

  private def matcherCancelOrder(node: Node, pair: AssetPair, orderId: String): String = {
    val privateKey = PrivateKeyAccount(Base58.decode(node.accountSeed).get)
    val publicKey = PublicKeyAccount(Base58.decode(node.publicKey).get)
    val request = CancelOrderRequest(publicKey, Base58.decode(orderId).get, Array.emptyByteArray)
    val signedRequest = CancelOrderRequest.sign(request, privateKey)
    val futureResult = matcherNode.cancelOrder(pair.amountAssetStr, pair.priceAssetStr, signedRequest)

    val result = Await.result(futureResult, 1.minute)

    result.status
  }

}

object MatcherTestSuite {
  val ForbiddenAssetId = "FdbnAsset"

  private val dockerConfigs = Docker.NodeConfigs.getConfigList("nodes").asScala

  private val generatingMatcherConfig = ConfigFactory.parseString(
    s"""
      |vsys.matcher {
      |  enable=yes
      |  account="3HevUqdcHuiLvpeVLo4sGVqxSsZczJuCYHo"
      |  bind-address="0.0.0.0"
      |  order-match-tx-fee = 300000
      |  blacklisted-assets = [$ForbiddenAssetId]
      |}
    """.stripMargin)

  private val nonGeneratingPeersConfig = ConfigFactory.parseString(
    """
      |vsys.miner.enable=no
    """.stripMargin
  )

  val AssetQuantity: Long = 1000

  val MatcherFee: Long = 300000
  val TransactionFee: Long = 300000

  val Waves: Long = 100000000L

  val Configs: Seq[Config] = Seq(generatingMatcherConfig.withFallback(dockerConfigs.head)) ++
    Random.shuffle(dockerConfigs.tail).take(2).map(nonGeneratingPeersConfig.withFallback(_))
}
