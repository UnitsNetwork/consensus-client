package units.client.engine

import cats.syntax.either.*
import cats.syntax.traverse.*
import play.api.libs.json.*
import sttp.client3.*
import units.client.JsonRpcClient
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.HttpEngineApiClient.*
import units.client.engine.model.*
import units.client.engine.model.ForkChoiceUpdatedRequest.ForkChoiceAttributes
import units.eth.EthAddress
import units.{BlockHash, ClientConfig, ClientError, Job}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class HttpEngineApiClient(val config: ClientConfig, val backend: SttpBackend[Identity, ?]) extends EngineApiClient with JsonRpcClient {

  val apiUrl = uri"http://${config.executionClientAddress}:${config.engineApiPort}"

  def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash): Job[String] = {
    sendEngineRequest[ForkChoiceUpdatedRequest, ForkChoiceUpdatedResponse](
      ForkChoiceUpdatedRequest(blockHash, finalizedBlockHash, None),
      BlockExecutionTimeout
    )
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

  def getBlockByNumber(number: BlockNumber): Job[Option[EcBlock]] = {
    for {
      json      <- getBlockByNumberJson(number.str)
      blockMeta <- json.traverse(parseJson[EcBlock](_))
    } yield blockMeta
  }

  def getBlockByHash(hash: BlockHash): Job[Option[EcBlock]] = {
    sendRequest[GetBlockByHashRequest, EcBlock](GetBlockByHashRequest(hash))
      .leftMap(err => ClientError(s"Error getting block by hash $hash: $err"))
  }

  def getBlockByHashJson(hash: BlockHash): Job[Option[JsObject]] = {
    sendRequest[GetBlockByHashRequest, JsObject](GetBlockByHashRequest(hash))
      .leftMap(err => ClientError(s"Error getting block json by hash $hash: $err"))
  }

  def getLastExecutionBlock: Job[EcBlock] = for {
    lastEcBlockOpt <- getBlockByNumber(BlockNumber.Latest)
    lastEcBlock    <- Either.fromOption(lastEcBlockOpt, ClientError("Impossible: EC doesn't have blocks"))
  } yield lastEcBlock

  def blockExists(hash: BlockHash): Job[Boolean] =
    getBlockByHash(hash).map(_.isDefined)

  private def getBlockByNumberJson(number: String): Job[Option[JsObject]] = {
    sendRequest[GetBlockByNumberRequest, JsObject](GetBlockByNumberRequest(number))
      .leftMap(err => ClientError(s"Error getting block by number $number: $err"))
  }

  override def getLogs(hash: BlockHash, address: EthAddress, topic: String): Job[List[GetLogsResponseEntry]] =
    sendRequest[GetLogsRequest, List[GetLogsResponseEntry]](GetLogsRequest(hash, address, List(topic)))
      .leftMap(err => ClientError(s"Error getting block logs by hash $hash: $err"))
      .map(_.getOrElse(List.empty))

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
