package com.wavesplatform.api

import cats.syntax.either.*
import cats.syntax.option.*
import com.wavesplatform.account.Address
import com.wavesplatform.api.LoggingBackend.{LoggingOptions, LoggingOptionsTag}
import com.wavesplatform.api.NodeHttpApi.*
import com.wavesplatform.api.http.ApiMarshallers.TransactionJsonWrites
import com.wavesplatform.api.http.TransactionsApiRoute.ApplicationStatus
import com.wavesplatform.api.http.`X-Api-Key`
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.state.DataEntry.Format
import com.wavesplatform.state.{DataEntry, EmptyDataEntry, Height}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.Transaction
import com.wavesplatform.utils.ScorexLogging
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.*
import sttp.client3.*
import sttp.client3.playJson.*
import sttp.model.{StatusCode, Uri}
import units.docker.WavesNodeContainer.AverageBlockDelay
import units.test.IntegrationTestEventually

import scala.util.chaining.scalaUtilChainingOps

class NodeHttpApi(apiUri: Uri, backend: SttpBackend[Identity, ?], apiKeyValue: String = DefaultApiKeyValue)
    extends IntegrationTestEventually
    with Matchers
    with ScorexLogging {
  def blockHeader(atHeight: Int): Option[BlockHeaderResponse] = {
    val loggingOptions: LoggingOptions = LoggingOptions()
    log.debug(s"${loggingOptions.prefix} blockHeader($atHeight)")
    basicRequest
      .get(uri"$apiUri/blocks/headers/at/$atHeight")
      .response(asJson[BlockHeaderResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(_, StatusCode.NotFound))     => none
      case Left(HttpError(body, statusCode))           => throw new RuntimeException(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => throw new RuntimeException(s"failed to parse response $body: $error")
      case Right(r)                                    => r.some
    }
  }

  def waitForHeight(atLeast: Int): Height = {
    val loggingOptions: LoggingOptions = LoggingOptions()
    log.debug(s"${loggingOptions.prefix} waitForHeight($atLeast)")
    val currHeight = heightImpl()(loggingOptions)
    if (currHeight >= atLeast) currHeight
    else {
      val subsequentLoggingOptions = loggingOptions.copy(logRequest = false)
      Thread.sleep(patienceConfig.interval.toMillis)
      eventually(timeout(AverageBlockDelay * (atLeast - currHeight).min(1) * 2.5)) {
        val h = heightImpl()(subsequentLoggingOptions)
        h should be >= atLeast
        h
      }
    }
  }

  def height: Height = {
    val loggingOptions: LoggingOptions = LoggingOptions()
    log.debug(s"${loggingOptions.prefix} height")
    heightImpl()
  }

  protected def heightImpl()(implicit loggingOptions: LoggingOptions = LoggingOptions()): Height =
    basicRequest
      .get(uri"$apiUri/blocks/height")
      .response(asJson[HeightResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(e)  => throw e
      case Right(r) => r.height
    }

  def broadcastAndWait(txn: Transaction): TransactionInfoResponse = {
    implicit val loggingOptions: LoggingOptions = LoggingOptions(logResponseBody = false)
    log.debug(s"${loggingOptions.prefix} broadcastAndWait($txn)")
    broadcastImpl(txn)(loggingOptions.copy(logRequestBody = false)).left.foreach { e =>
      throw new RuntimeException(s"Can't broadcast ${txn.id()}: code=${e.error}, message=${e.message}")
    }
    waitFor(txn.id())
  }

  def waitFor(txnId: ByteStr)(implicit loggingOptions: LoggingOptions = LoggingOptions()): TransactionInfoResponse = {
    var attempt = 0
    eventually {
      attempt += 1
      val subsequentLoggingOptions = loggingOptions.copy(logRequest = attempt == 2)
      transactionInfoImpl(txnId)(subsequentLoggingOptions) match {
        case Some(r) if r.applicationStatus == ApplicationStatus.Succeeded => r
        case r => fail(s"Expected ${ApplicationStatus.Succeeded} status, got: ${r.map(_.applicationStatus)}")
      }
    }
  }

  def broadcast(txn: Transaction): Either[ErrorResponse, Transaction] = {
    implicit val loggingOptions: LoggingOptions = LoggingOptions()
    log.debug(s"${loggingOptions.prefix} broadcast($txn)")
    broadcastImpl(txn)
  }

  protected def broadcastImpl[T <: Transaction](txn: T)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Either[ErrorResponse, T] =
    basicRequest
      .post(uri"$apiUri/transactions/broadcast")
      .body(txn: Transaction)
      .response(asJsonEither[ErrorResponse, BroadcastResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(e, _)) => e.asLeft
      case Left(e)               => throw new RuntimeException(e)
      case _                     => txn.asRight
    }

  protected def transactionInfoImpl(id: ByteStr)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Option[TransactionInfoResponse] =
    basicRequest
      .get(uri"$apiUri/transactions/info/$id")
      .response(asJson[TransactionInfoResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(_, StatusCode.NotFound))     => none
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"failed to parse response $body: $error")
      case Right(r)                                    => r.some
    }

  def dataByKey(address: Address, key: String)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Option[DataEntry[?]] = {
    log.debug(s"${loggingOptions.prefix} dataByKey($address, $key)")
    basicRequest
      .get(uri"$apiUri/addresses/data/$address/$key")
      .response(asJson[DataEntry[?]])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(_, StatusCode.NotFound))     => none
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"failed to parse response $body: $error")
      case Right(response) =>
        response match {
          case _: EmptyDataEntry => none
          case _                 => response.some
        }
    }
  }

  def balance(address: Address, asset: IssuedAsset)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Long = {
    log.debug(s"${loggingOptions.prefix} balance($address, $asset)")
    basicRequest
      .get(uri"$apiUri/assets/balance/$address/$asset")
      .response(asJson[AssetBalanceResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(_, StatusCode.NotFound))     => 0L
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"failed to parse response $body: $error")
      case Right(r)                                    => r.balance
    }
  }

  def assetQuantity(asset: IssuedAsset)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Long = {
    log.debug(s"${loggingOptions.prefix} assetQuantity($asset)")
    basicRequest
      .get(uri"$apiUri/assets/details/$asset?full=false")
      .response(asJson[AssetDetailsResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"failed to parse response $body: $error")
      case Right(r)                                    => r.quantity
    }
  }

  def waitForConnectedPeers(atLeast: Int): Unit = {
    implicit val loggingOptions: LoggingOptions = LoggingOptions(logRequestBody = false)
    log.debug(s"${loggingOptions.prefix} waitForConnectedPeers($atLeast)")
    var attempt = 0
    eventually {
      attempt += 1
      val subsequentLoggingOptions = loggingOptions.copy(logRequest = attempt == 2)
      connectedPeersImpl()(subsequentLoggingOptions).tap { x =>
        if (x < atLeast) fail(s"Expected at least $atLeast, got $x")
      }
    }
  }

  protected def connectedPeersImpl()(implicit loggingOptions: LoggingOptions = LoggingOptions()): Int =
    basicRequest
      .get(uri"$apiUri/peers/connected")
      .response(asJson[ConnectedPeersResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"failed to parse response $body: $error")
      case Right(r)                                    => r.peers.length
    }

  def evaluateExpr(address: Address, expr: String): JsObject = {
    implicit val loggingOptions: LoggingOptions = LoggingOptions()
    log.debug(s"${loggingOptions.prefix} evaluateExpr($address, '$expr')")
    basicRequest
      .post(uri"$apiUri/utils/script/evaluate/$address")
      .body(Json.obj("expr" -> expr))
      .response(asJson[JsObject])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(e)  => throw new RuntimeException(e)
      case Right(r) => r
    }
  }

  def createWalletAddress(): Unit = {
    implicit val loggingOptions: LoggingOptions = LoggingOptions()
    log.debug(s"${loggingOptions.prefix} createWalletAddress")
    basicRequest
      .post(uri"$apiUri/addresses")
      .header(`X-Api-Key`.name, apiKeyValue)
      .response(asString)
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
  }

  def rollback(to: Height): Unit = {
    implicit val loggingOptions: LoggingOptions = LoggingOptions()
    log.debug(s"${loggingOptions.prefix} rollback($to)")
    basicRequest
      .post(uri"$apiUri/debug/rollback")
      .header(`X-Api-Key`.name, apiKeyValue)
      .body(
        Json.obj(
          "rollbackTo"              -> to,
          "returnTransactionsToUtx" -> false
        )
      )
      .response(asString)
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
  }

  def print(message: String): Unit =
    basicRequest
      .post(uri"$apiUri/debug/print")
      .header(`X-Api-Key`.name, apiKeyValue)
      .body(Json.obj("message" -> message))
      .response(ignore)
      .send(backend)
}

object NodeHttpApi {
  val DefaultApiKeyValue = "testapi"

  case class BlockHeaderResponse(VRF: String)
  object BlockHeaderResponse {
    implicit val blockHeaderResponseFormat: OFormat[BlockHeaderResponse] = Json.format[BlockHeaderResponse]
  }

  case class HeightResponse(height: Height)
  object HeightResponse {
    implicit val heightResponseFormat: OFormat[HeightResponse] = Json.format[HeightResponse]
  }

  case class BroadcastResponse(id: String)
  object BroadcastResponse {
    implicit val broadcastResponseFormat: OFormat[BroadcastResponse] = Json.format[BroadcastResponse]
  }

  case class TransactionInfoResponse(height: Height, applicationStatus: String)
  object TransactionInfoResponse {
    implicit val transactionInfoResponseFormat: OFormat[TransactionInfoResponse] = Json.format[TransactionInfoResponse]
  }

  case class AssetBalanceResponse(balance: Long)
  object AssetBalanceResponse {
    implicit val assetBalanceResponseFormat: OFormat[AssetBalanceResponse] = Json.format[AssetBalanceResponse]
  }

  case class AssetDetailsResponse(quantity: Long)
  object AssetDetailsResponse {
    implicit val assetDetailsResponseFormat: OFormat[AssetDetailsResponse] = Json.format[AssetDetailsResponse]
  }

  case class ConnectedPeersResponse(peers: List[JsObject])
  object ConnectedPeersResponse {
    implicit val connectedPeersResponseFormat: OFormat[ConnectedPeersResponse] = Json.format[ConnectedPeersResponse]
  }

  case class ErrorResponse(error: Int, message: String)
  object ErrorResponse {
    implicit val errorResponseFormat: OFormat[ErrorResponse] = Json.format[ErrorResponse]
  }
}
