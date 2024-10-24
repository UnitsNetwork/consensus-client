package com.wavesplatform.api

import cats.syntax.option.*
import com.wavesplatform.account.Address
import com.wavesplatform.api.NodeHttpApi.{HeightResponse, TransactionInfoResponse}
import com.wavesplatform.api.http.ApiMarshallers.TransactionJsonWrites
import com.wavesplatform.api.http.TransactionsApiRoute.ApplicationStatus
import com.wavesplatform.state.DataEntry.Format
import com.wavesplatform.state.{DataEntry, EmptyDataEntry, TransactionId}
import com.wavesplatform.transaction.Transaction
import com.wavesplatform.utils.ScorexLogging
import play.api.libs.json.*
import sttp.client3.*
import sttp.client3.playJson.*
import sttp.model.{StatusCode, Uri}

import scala.concurrent.duration.DurationInt

class NodeHttpApi(apiUri: Uri, backend: SttpBackend[Identity, ?]) extends ScorexLogging {
  private val averageBlockDelay = 18.seconds

  def height: Int =
    basicRequest
      .get(uri"$apiUri/blocks/height")
      .response(asJson[HeightResponse])
      .send(backend)
      .body match {
      case Left(e)  => throw e
      case Right(r) => r.height
    }

  def waitForHeight(atLeast: Int): Int = {
    val currHeight = height
    if (currHeight >= atLeast) currHeight
    else
      WithRetries(
        maxAttempts = (averageBlockDelay.toSeconds.toInt * (atLeast - currHeight) * 2.5).toInt,
        message = s"waitForHeight($atLeast)"
      ).until(height) {
        case h if h >= atLeast => h
      }
  }

  def broadcast[T <: Transaction](txn: T): T =
    basicRequest
      .post(uri"$apiUri/transactions/broadcast")
      .body(txn: Transaction)
      .send(backend)
      .body match {
      case Left(e) => throw new RuntimeException(e)
      case _       => txn
    }

  def broadcastAndWait(txn: Transaction): TransactionInfoResponse = {
    broadcast(txn)
    WithRetries(
      message = s"broadcastAndWait(${txn.id()})"
    ).until(transactionInfo(TransactionId(txn.id()))) {
      case Some(info) if info.applicationStatus == ApplicationStatus.Succeeded => info
    }
  }

  def transactionInfo(id: TransactionId): Option[TransactionInfoResponse] =
    basicRequest
      .get(uri"$apiUri/transactions/info/$id")
      .response(asJson[TransactionInfoResponse])
      .send(backend)
      .body match {
      case Left(HttpError(_, StatusCode.NotFound))     => none
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"failed to parse response $body: $error")
      case Right(r)                                    => r.some
    }

  def getDataByKey(address: Address, key: String): Option[DataEntry[?]] =
    basicRequest
      .get(uri"$apiUri/addresses/data/$address/$key")
      .response(asJson[DataEntry[?]])
      .send(backend)
      .body match {
      case Left(HttpError(_, StatusCode.NotFound))     => none
      case Left(HttpError(body, statusCode))           => fail(s"Server returned error $body with status ${statusCode.code}")
      case Left(DeserializationException(body, error)) => fail(s"failed to parse response $body: $error")
      case Right(r) =>
        r match {
          case _: EmptyDataEntry => none
          case _                 => r.some
        }
    }

  private def fail(message: String): Nothing = throw new RuntimeException(s"JSON-RPC error: $message")
}

object NodeHttpApi {
  case class HeightResponse(height: Int)
  object HeightResponse {
    implicit val heightResponseFormat: OFormat[HeightResponse] = Json.format[HeightResponse]
  }

  case class TransactionInfoResponse(height: Int, applicationStatus: String)
  object TransactionInfoResponse {
    implicit val transactionInfoResponseFormat: OFormat[TransactionInfoResponse] = Json.format[TransactionInfoResponse]
  }
}
