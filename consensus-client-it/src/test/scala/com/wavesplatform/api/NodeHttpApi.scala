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
import com.wavesplatform.transaction.{Asset, Transaction}
import com.wavesplatform.utils.ScorexLogging
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.*
import sttp.client3.*
import sttp.client3.playJson.*
import sttp.model.{StatusCode, Uri}
import units.docker.WavesNodeContainer.MaxBlockDelay
import units.test.IntegrationTestEventually

class NodeHttpApi(apiUri: Uri, backend: SttpBackend[Identity, ?], apiKeyValue: String = DefaultApiKeyValue)
    extends IntegrationTestEventually
    with Matchers
    with ScorexLogging {
  def blockHeader(atHeight: Height)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Option[BlockHeaderResponse] = {
    if (loggingOptions.logRequest) log.debug(s"${loggingOptions.prefix} blockHeader($atHeight)")
    basicRequest
      .get(uri"$apiUri/blocks/headers/at/$atHeight")
      .response(asJson[BlockHeaderResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(_, StatusCode.NotFound)) =>
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} None")
        none
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"Failed to parse response $body: $error")
      case Right(r) =>
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} $r")
        r.some
    }
  }

  def waitForHeight(atLeast: Height)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Height = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} waitForHeight($atLeast)")
    val subsequentLoggingOptions = loggingOptions.copy(logCall = false, logResult = false, logRequest = false)
    val currHeight               = height()(using subsequentLoggingOptions)
    if (currHeight >= atLeast) currHeight
    else {
      Thread.sleep(patienceConfig.interval.toMillis)
      val waitBlocks = (atLeast - currHeight).max(1)
      eventually(timeout(MaxBlockDelay * waitBlocks)) {
        val h = height()(using subsequentLoggingOptions)
        h should be >= atLeast
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} $h")
        h
      }
    }
  }

  def height()(implicit loggingOptions: LoggingOptions = LoggingOptions()): Height = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} height")
    basicRequest
      .get(uri"$apiUri/blocks/height")
      .response(asJson[HeightResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(e) => fail(e)
      case Right(r) =>
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} ${r.height}")
        r.height
    }
  }

  def broadcastAndWait(
      txn: Transaction
  )(implicit loggingOptions: LoggingOptions = LoggingOptions(logResponseBody = false)): TransactionInfoResponse = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} broadcastAndWait")
    broadcast(txn)(using loggingOptions.copy(logRequest = false)).left.foreach { e =>
      fail(s"Can't broadcast ${txn.id()}: code=${e.error}, message=${e.message}")
    }
    waitForSucceeded(txn.id())
  }

  def broadcast[T <: Transaction](txn: T)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Either[ErrorResponse, T] = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} broadcast($txn)")
    basicRequest
      .post(uri"$apiUri/transactions/broadcast")
      .body(txn: Transaction)
      .response(asJsonEither[ErrorResponse, BroadcastResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(e, _)) => e.asLeft
      case Left(e)               => fail(e)
      case _                     => txn.asRight
    }
  }

  def waitForSucceeded(txnId: ByteStr)(implicit loggingOptions: LoggingOptions = LoggingOptions()): TransactionInfoResponse = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} waitFor($txnId)")
    var attempt = 0
    eventually {
      attempt += 1
      val subsequentLoggingOptions = loggingOptions.copy(logCall = false, logResult = false, logRequest = attempt == 1)
      transactionInfo(txnId)(using subsequentLoggingOptions) match {
        case Some(r) if r.applicationStatus == ApplicationStatus.Succeeded =>
          if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} $r")
          r

        case r => fail(s"Expected ${ApplicationStatus.Succeeded} status, got: ${r.map(_.applicationStatus)}")
      }
    }
  }

  def transactionInfo(txnId: ByteStr)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Option[TransactionInfoResponse] = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} transactionInfo($txnId)")
    basicRequest
      .get(uri"$apiUri/transactions/info/$txnId")
      .response(asJson[TransactionInfoResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(_, StatusCode.NotFound)) =>
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} None")
        none
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"Failed to parse response $body: $error")
      case Right(r) =>
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} $r")
        r.some
    }
  }

  def dataByKey(address: Address, key: String)(implicit loggingOptions: LoggingOptions = LoggingOptions(logRequest = false)): Option[DataEntry[?]] = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} dataByKey($address, $key)")
    basicRequest
      .get(uri"$apiUri/addresses/data/$address/$key")
      .response(asJson[DataEntry[?]])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(_, StatusCode.NotFound)) =>
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} None")
        none
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"Failed to parse response $body: $error")
      case Right(r) =>
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} $r")
        r match {
          case _: EmptyDataEntry => none
          case _                 => r.some
        }
    }
  }

  def balance(address: Address, asset: Asset)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Long = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} balance($address, $asset)")
    basicRequest
      .get(asset match {
        case Asset.Waves     => uri"$apiUri/addresses/balance/$address"
        case IssuedAsset(id) => uri"$apiUri/assets/balance/$address/$id"
      })
      .response(asJson[BalanceResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(_, StatusCode.NotFound)) =>
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} 0")
        0L
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"Failed to parse response $body: $error")
      case Right(r) =>
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} ${r.balance}")
        r.balance
    }
  }

  def assetQuantity(asset: IssuedAsset)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Long = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} assetQuantity($asset)")
    basicRequest
      .get(uri"$apiUri/assets/details/$asset?full=false")
      .response(asJson[AssetDetailsResponse])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"Failed to parse response $body: $error")
      case Right(r) =>
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} ${r.quantity}")
        r.quantity
    }
  }

  def evaluateExpr(address: Address, expr: String)(implicit loggingOptions: LoggingOptions = LoggingOptions(logRequest = false)): JsObject = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} evaluateExpr($address, '$expr')")
    basicRequest
      .post(uri"$apiUri/utils/script/evaluate/$address")
      .body(Json.obj("expr" -> expr))
      .response(asJson[JsObject])
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
      .body match {
      case Left(e) => fail(e)
      case Right(r) =>
        if (loggingOptions.logResult) log.debug(s"${loggingOptions.prefix} ${(r \ "result").getOrElse(r)}")
        r
    }
  }

  def createWalletAddress()(implicit loggingOptions: LoggingOptions = LoggingOptions()): Unit = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} createWalletAddress")
    basicRequest
      .post(uri"$apiUri/addresses")
      .header(`X-Api-Key`.name, apiKeyValue)
      .response(asString)
      .tag(LoggingOptionsTag, loggingOptions)
      .send(backend)
  }

  def rollback(to: Height)(implicit loggingOptions: LoggingOptions = LoggingOptions()): Unit = {
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} rollback($to)")
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
    given Reads[Height] = Reads.IntReads.map(Height.apply)
    given OFormat[HeightResponse] = Json.format[HeightResponse]
  }

  case class BroadcastResponse(id: String)
  object BroadcastResponse {
    implicit val broadcastResponseFormat: OFormat[BroadcastResponse] = Json.format[BroadcastResponse]
  }

  case class TransactionInfoResponse(height: Height, applicationStatus: String)
  object TransactionInfoResponse {
    given Reads[Height] = Reads.IntReads.map(Height.apply)
    given OFormat[TransactionInfoResponse] = Json.format[TransactionInfoResponse]
  }

  case class BalanceResponse(balance: Long)
  object BalanceResponse {
    implicit val assetBalanceResponseFormat: OFormat[BalanceResponse] = Json.format[BalanceResponse]
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
