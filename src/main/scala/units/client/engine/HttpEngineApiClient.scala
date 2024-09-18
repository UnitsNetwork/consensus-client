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
import units.{BlockHash, ClientConfig, ClientError, JobResult}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class HttpEngineApiClient(val config: ClientConfig, val backend: SttpBackend[Identity, ?]) extends EngineApiClient with JsonRpcClient {

  val apiUrl: Uri = uri"${config.executionClientAddress}"

  def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash): JobResult[PayloadStatus] = {
    sendEngineRequest[ForkChoiceUpdatedRequest, ForkChoiceUpdatedResponse](
      ForkChoiceUpdatedRequest(blockHash, finalizedBlockHash, None),
      BlockExecutionTimeout
    )
      .flatMap {
        case ForkChoiceUpdatedResponse(ps @ PayloadState(Valid | Syncing, _, _), None) => Right(ps.status)
        case ForkChoiceUpdatedResponse(PayloadState(_, _, Some(validationError)), _) =>
          Left(ClientError(s"Payload validation error: $validationError"))
        case ForkChoiceUpdatedResponse(payloadState, _) => Left(ClientError(s"Unexpected payload status ${payloadState.status}"))
      }
  }

  def forkChoiceUpdateWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal]
  ): JobResult[PayloadId] = {
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
        Left(ClientError(s"Payload id for $lastBlockHash is not defined"))
      case ForkChoiceUpdatedResponse(PayloadState(_, _, Some(validationError)), _) =>
        Left(ClientError(s"Payload validation error for $lastBlockHash: $validationError"))
      case ForkChoiceUpdatedResponse(payloadState, _) =>
        Left(ClientError(s"Unexpected payload status for $lastBlockHash: ${payloadState.status}"))
    }
  }

  def getPayload(payloadId: PayloadId): JobResult[JsObject] = {
    sendEngineRequest[GetPayloadRequest, GetPayloadResponse](GetPayloadRequest(payloadId), NonBlockExecutionTimeout).map(_.executionPayload)
  }

  def applyNewPayload(payload: JsObject): JobResult[Option[BlockHash]] = {
    sendEngineRequest[NewPayloadRequest, PayloadState](NewPayloadRequest(payload), BlockExecutionTimeout).flatMap {
      case PayloadState(_, _, Some(validationError))     => Left(ClientError(s"Payload validation error: $validationError"))
      case PayloadState(Valid, Some(latestValidHash), _) => Right(Some(latestValidHash))
      case PayloadState(Syncing, latestValidHash, _)     => Right(latestValidHash)
      case PayloadState(status, None, _)                 => Left(ClientError(s"Latest valid hash is not defined at status $status"))
      case PayloadState(status, _, _)                    => Left(ClientError(s"Unexpected payload status: $status"))
    }
  }

  def getPayloadBodyByHash(hash: BlockHash): JobResult[Option[JsObject]] = {
    sendEngineRequest[GetPayloadBodyByHash, JsArray](GetPayloadBodyByHash(hash), NonBlockExecutionTimeout)
      .map(_.value.headOption.flatMap(_.asOpt[JsObject]))
  }

  def getBlockByNumber(number: BlockNumber): JobResult[Option[EcBlock]] = {
    for {
      json      <- getBlockByNumberJson(number.str)
      blockMeta <- json.traverse(parseJson[EcBlock](_))
    } yield blockMeta
  }

  def getBlockByHash(hash: BlockHash): JobResult[Option[EcBlock]] = {
    sendRequest[GetBlockByHashRequest, EcBlock](GetBlockByHashRequest(hash))
      .leftMap(err => ClientError(s"Error getting block by hash $hash: $err"))
  }

  def getBlockByHashJson(hash: BlockHash): JobResult[Option[JsObject]] = {
    sendRequest[GetBlockByHashRequest, JsObject](GetBlockByHashRequest(hash))
      .leftMap(err => ClientError(s"Error getting block json by hash $hash: $err"))
  }

  def getLastExecutionBlock: JobResult[EcBlock] = for {
    lastEcBlockOpt <- getBlockByNumber(BlockNumber.Latest)
    lastEcBlock    <- Either.fromOption(lastEcBlockOpt, ClientError("Impossible: EC doesn't have blocks"))
  } yield lastEcBlock

  def blockExists(hash: BlockHash): JobResult[Boolean] =
    getBlockByHash(hash).map(_.isDefined)

  private def getBlockByNumberJson(number: String): JobResult[Option[JsObject]] = {
    sendRequest[GetBlockByNumberRequest, JsObject](GetBlockByNumberRequest(number))
      .leftMap(err => ClientError(s"Error getting block by number $number: $err"))
  }

  override def getLogs(hash: BlockHash, address: EthAddress, topic: String): JobResult[List[GetLogsResponseEntry]] =
    sendRequest[GetLogsRequest, List[GetLogsResponseEntry]](GetLogsRequest(hash, address, List(topic)))
      .leftMap(err => ClientError(s"Error getting block logs by hash $hash: $err"))
      .map(_.getOrElse(List.empty))

  private def sendEngineRequest[A: Writes, B: Reads](request: A, timeout: FiniteDuration): JobResult[B] = {
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
