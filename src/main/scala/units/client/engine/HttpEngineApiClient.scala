package units.client.engine

import units.client.JsonRpcClient
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.HttpEngineApiClient.*
import units.client.engine.model.*
import units.client.engine.model.ForkChoiceUpdatedRequest.ForkChoiceAttributes
import units.eth.EthAddress
import units.{BlockHash, ClientConfig, ClientError, Job}
import play.api.libs.json.*
import sttp.client3.*

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class HttpEngineApiClient(val config: ClientConfig, val backend: SttpBackend[Identity, ?]) extends EngineApiClient with JsonRpcClient {

  val apiUrl = uri"http://${config.executionClientAddress}:${config.engineApiPort}"

  def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash): Job[String] = {
    sendEngineRequest[ForkChoiceUpdatedRequest, ForkChoiceUpdatedResponse](ForkChoiceUpdatedRequest(blockHash, finalizedBlockHash, None), BlockExecutionTimeout)
      .flatMap {
        case ForkChoiceUpdatedResponse(PayloadStatus(status, _, _), None) if status == "SYNCING" || status == "VALID" => Right(status)
        case ForkChoiceUpdatedResponse(PayloadStatus(_, _, Some(validationError)), _) =>
          Left(ClientError(s"Payload validation error: $validationError"))
        case ForkChoiceUpdatedResponse(payloadStatus, _) => Left(ClientError(s"Unexpected payload status ${payloadStatus.status}"))
      }
  }

  def forkChoiceUpdateWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal]
  ): Job[PayloadId] = {
    sendEngineRequest[ForkChoiceUpdatedRequest, ForkChoiceUpdatedResponse](
      ForkChoiceUpdatedRequest(
        lastBlockHash,
        finalizedBlockHash,
        Some(ForkChoiceAttributes(unixEpochSeconds, suggestedFeeRecipient, prevRandao, withdrawals))
      ),
      BlockExecutionTimeout
    ).flatMap {
      case ForkChoiceUpdatedResponse(payloadStatus, Some(payloadId)) if payloadStatus.status == "VALID" =>
        Right(payloadId)
      case ForkChoiceUpdatedResponse(_, None) =>
        Left(ClientError(s"Payload id for $lastBlockHash is not defined"))
      case ForkChoiceUpdatedResponse(PayloadStatus(_, _, Some(validationError)), _) =>
        Left(ClientError(s"Payload validation error for $lastBlockHash: $validationError"))
      case ForkChoiceUpdatedResponse(payloadStatus, _) =>
        Left(ClientError(s"Unexpected payload status for $lastBlockHash: ${payloadStatus.status}"))
    }
  }

  def getPayload(payloadId: PayloadId): Job[JsObject] = {
    sendEngineRequest[GetPayloadRequest, GetPayloadResponse](GetPayloadRequest(payloadId), NonBlockExecutionTimeout).map(_.executionPayload)
  }

  def applyNewPayload(payload: JsObject): Job[Option[BlockHash]] = {
    sendEngineRequest[NewPayloadRequest, PayloadStatus](NewPayloadRequest(payload), BlockExecutionTimeout).flatMap {
      case PayloadStatus(_, _, Some(validationError))                           => Left(ClientError(s"Payload validation error: $validationError"))
      case PayloadStatus(status, Some(latestValidHash), _) if status == "VALID" => Right(Some(latestValidHash))
      case PayloadStatus(status, latestValidHash, _) if status == "SYNCING"     => Right(latestValidHash)
      case PayloadStatus(status, None, _) => Left(ClientError(s"Latest valid hash is not defined at status $status"))
      case PayloadStatus(status, _, _)    => Left(ClientError(s"Unexpected payload status: $status"))
    }
  }

  def getPayloadBodyByHash(hash: BlockHash): Job[Option[JsObject]] = {
    sendEngineRequest[GetPayloadBodyByHash, JsArray](GetPayloadBodyByHash(hash), NonBlockExecutionTimeout)
      .map(_.value.headOption.flatMap(_.asOpt[JsObject]))
  }

  private def sendEngineRequest[A: Writes, B: Reads](request: A, timeout: FiniteDuration): Job[B] = {
    sendRequest(request, timeout) match {
      case Right(response) => response.toRight(ClientError(s"Unexpected engine API empty response"))
      case Left(err)       => Left(ClientError(s"Engine API request error: $err"))
    }
  }
}

object HttpEngineApiClient {
  private val BlockExecutionTimeout    = 8.seconds
  private val NonBlockExecutionTimeout = 1.second
}
