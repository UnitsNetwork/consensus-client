package units.client.engine

import cats.syntax.either.*
import cats.syntax.traverse.*
import play.api.libs.json.*
import sttp.client3.*
import sttp.model.Uri
import units.client.JsonRpcClient
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.HttpEngineApiClient.*
import units.client.engine.model.*
import units.client.engine.model.ForkChoiceUpdatedRequest.ForkChoiceAttributes
import units.client.engine.model.PayloadStatus.{Syncing, Valid}
import units.eth.EthAddress
import units.{BlockHash, ClientConfig}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class HttpEngineApiClient(val config: ClientConfig, val backend: SttpBackend[Identity, ?]) extends EngineApiClient with JsonRpcClient {

  val apiUrl: Uri = uri"${config.executionClientAddress}"

  def forkChoiceUpdated(blockHash: BlockHash, finalizedBlockHash: BlockHash): Either[String, PayloadStatus] = {
    sendEngineRequest[ForkChoiceUpdatedRequest, ForkChoiceUpdatedResponse](
      ForkChoiceUpdatedRequest(blockHash, finalizedBlockHash, None),
      BlockExecutionTimeout
    )
      .flatMap {
        case ForkChoiceUpdatedResponse(ps @ PayloadState(Valid | Syncing, _, _), None) => Right(ps.status)
        case ForkChoiceUpdatedResponse(PayloadState(_, _, Some(validationError)), _) =>
          Left(s"Payload validation error: $validationError")
        case ForkChoiceUpdatedResponse(payloadState, _) => Left(s"Unexpected payload status ${payloadState.status}")
      }
  }

  def forkChoiceUpdatedWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal]
  ): Either[String, PayloadId] = {
    sendEngineRequest[ForkChoiceUpdatedRequest, ForkChoiceUpdatedResponse](
      ForkChoiceUpdatedRequest(
        lastBlockHash,
        finalizedBlockHash,
        Some(ForkChoiceAttributes(unixEpochSeconds, suggestedFeeRecipient, prevRandao, withdrawals))
      ),
      BlockExecutionTimeout
    ).flatMap {
      case ForkChoiceUpdatedResponse(PayloadState(Valid, _, _), Some(payloadId)) =>
        Right(payloadId)
      case ForkChoiceUpdatedResponse(_, None) =>
        Left(s"Payload id for $lastBlockHash is not defined")
      case ForkChoiceUpdatedResponse(PayloadState(_, _, Some(validationError)), _) =>
        Left(s"Payload validation error for $lastBlockHash: $validationError")
      case ForkChoiceUpdatedResponse(payloadState, _) =>
        Left(s"Unexpected payload status for $lastBlockHash: ${payloadState.status}")
    }
  }

  def getPayload(payloadId: PayloadId): Either[String, JsObject] = {
    sendEngineRequest[GetPayloadRequest, GetPayloadResponse](GetPayloadRequest(payloadId), NonBlockExecutionTimeout).map(_.executionPayload)
  }

  def applyNewPayload(payloadJson: JsObject): Either[String, Option[BlockHash]] = {
    sendEngineRequest[NewPayloadRequest, PayloadState](NewPayloadRequest(payloadJson), BlockExecutionTimeout).flatMap {
      case PayloadState(_, _, Some(validationError))     => Left(s"Payload validation error: $validationError")
      case PayloadState(Valid, Some(latestValidHash), _) => Right(Some(latestValidHash))
      case PayloadState(Syncing, latestValidHash, _)     => Right(latestValidHash)
      case PayloadState(status, None, _)                 => Left(s"Latest valid hash is not defined at status $status")
      case PayloadState(status, _, _)                    => Left(s"Unexpected payload status: $status")
    }
  }

  def getPayloadBodyByHash(hash: BlockHash): Either[String, Option[JsObject]] = {
    sendEngineRequest[GetPayloadBodyByHash, JsArray](GetPayloadBodyByHash(hash), NonBlockExecutionTimeout)
      .map(_.value.headOption.flatMap(_.asOpt[JsObject]))
  }

  def getBlockByNumber(number: BlockNumber): Either[String, Option[ExecutionPayload]] = {
    for {
      json <- sendRequest[GetBlockByNumberRequest, JsObject](GetBlockByNumberRequest(number.str))
        .leftMap(err => s"Error getting payload by number $number: $err")
      blockMeta <- json.traverse(parseJson[ExecutionPayload](_))
    } yield blockMeta
  }

  def getBlockByHash(hash: BlockHash): Either[String, Option[ExecutionPayload]] = {
    sendRequest[GetBlockByHashRequest, ExecutionPayload](GetBlockByHashRequest(hash))
      .leftMap(err => s"Error getting payload by hash $hash: $err")
  }

  def getLatestBlock: Either[String, ExecutionPayload] = for {
    lastPayloadOpt <- getBlockByNumber(BlockNumber.Latest)
    lastPayload    <- Either.fromOption(lastPayloadOpt, "Impossible: EC doesn't have payloads")
  } yield lastPayload

  def getBlockJsonByHash(hash: BlockHash): Either[String, Option[JsObject]] = {
    sendRequest[GetBlockByHashRequest, JsObject](GetBlockByHashRequest(hash))
      .leftMap(err => s"Error getting block json by hash $hash: $err")
  }

  def getPayloadJsonDataByHash(hash: BlockHash): Either[String, PayloadJsonData] = {
    for {
      blockJsonOpt       <- getBlockJsonByHash(hash)
      blockJson          <- Either.fromOption(blockJsonOpt, "block not found")
      payloadBodyJsonOpt <- getPayloadBodyByHash(hash)
      payloadBodyJson    <- Either.fromOption(payloadBodyJsonOpt, "payload body not found")
    } yield PayloadJsonData(blockJson, payloadBodyJson)
  }

  def getLogs(hash: BlockHash, address: EthAddress, topic: String): Either[String, List[GetLogsResponseEntry]] =
    sendRequest[GetLogsRequest, List[GetLogsResponseEntry]](GetLogsRequest(hash, address, List(topic)))
      .leftMap(err => s"Error getting block logs by hash $hash: $err")
      .map(_.getOrElse(List.empty))

  private def sendEngineRequest[A: Writes, B: Reads](request: A, timeout: FiniteDuration): Either[String, B] = {
    sendRequest(request, timeout) match {
      case Right(response) => response.toRight(s"Unexpected engine API empty response")
      case Left(err)       => Left(s"Engine API request error: $err")
    }
  }
}

object HttpEngineApiClient {
  private val BlockExecutionTimeout    = 8.seconds
  private val NonBlockExecutionTimeout = 1.second
}
