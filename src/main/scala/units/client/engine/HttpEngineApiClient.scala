package units.client.engine

import cats.syntax.either.*
import cats.syntax.traverse.*
import play.api.libs.json.*
import sttp.client3.*
import units.client.JsonRpcClient
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.HttpEngineApiClient.*
import units.client.engine.model.*
import units.client.engine.model.ForkchoiceUpdatedRequest.ForkChoiceAttributes
import units.client.engine.model.PayloadStatus.{Syncing, Valid}
import units.eth.EthAddress
import units.{BlockHash, ClientError, JobResult}
import units.client.engine.model.given 

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class HttpEngineApiClient(val config: JsonRpcClient.Config, val backend: SttpBackend[Identity, ?]) extends EngineApiClient with JsonRpcClient {
  def forkchoiceUpdated(blockHash: BlockHash, finalizedBlockHash: BlockHash, requestId: Int): JobResult[PayloadStatus] = {
    sendEngineRequest[ForkchoiceUpdatedRequest, ForkChoiceUpdatedResponse](
      ForkchoiceUpdatedRequest(blockHash, finalizedBlockHash, None, requestId),
      BlockExecutionTimeout,
      requestId
    )
      .flatMap {
        case ForkChoiceUpdatedResponse(ps @ PayloadState(Valid | Syncing, _, _), None) => Right(ps.status)
        case ForkChoiceUpdatedResponse(PayloadState(_, _, Some(validationError)), _) =>
          Left(ClientError(s"Payload validation error: $validationError"))
        case ForkChoiceUpdatedResponse(payloadState, _) => Left(ClientError(s"Unexpected payload status ${payloadState.status}"))
      }
  }

  def forkchoiceUpdatedWithPayload(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal],
      transactions: Vector[String],
      requestId: Int
  ): JobResult[PayloadId] = {
    sendEngineRequest[ForkchoiceUpdatedRequest, ForkChoiceUpdatedResponse](
      ForkchoiceUpdatedRequest(
        lastBlockHash,
        finalizedBlockHash,
        Some(ForkChoiceAttributes(unixEpochSeconds, suggestedFeeRecipient, prevRandao, withdrawals, transactions)),
        requestId
      ),
      BlockExecutionTimeout,
      requestId
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

  def getPayload(payloadId: PayloadId, requestId: Int): JobResult[JsObject] = {
    sendEngineRequest[GetPayloadRequest, GetPayloadResponse](GetPayloadRequest(payloadId, requestId), NonBlockExecutionTimeout, requestId).map(
      _.executionPayload - "withdrawalsRoot" - "depositRequests"
    )
  }

  def newPayload(payload: JsObject, requestId: Int): JobResult[Option[BlockHash]] = {
    sendEngineRequest[NewPayloadRequest, PayloadState](NewPayloadRequest(payload, requestId), BlockExecutionTimeout, requestId).flatMap {
      case PayloadState(_, _, Some(validationError))     => Left(ClientError(s"Payload validation error: $validationError"))
      case PayloadState(Valid, Some(latestValidHash), _) => Right(Some(latestValidHash))
      case PayloadState(Syncing, latestValidHash, _)     => Right(latestValidHash)
      case PayloadState(status, None, _)                 => Left(ClientError(s"Latest valid hash is not defined at status $status"))
      case PayloadState(status, _, _)                    => Left(ClientError(s"Unexpected payload status: $status"))
    }
  }

  def getPayloadBodyByHash(hash: BlockHash, requestId: Int): JobResult[Option[JsObject]] = {
    sendEngineRequest[GetPayloadBodyByHash, JsArray](GetPayloadBodyByHash(hash, requestId), NonBlockExecutionTimeout, requestId)
      .map(_.value.headOption.flatMap(_.asOpt[JsObject]))
  }

  def getBlockByNumber(number: BlockNumber, requestId: Int): JobResult[Option[EcBlock]] = {
    for {
      json      <- getBlockByNumberJson(number.str, requestId)
      blockMeta <- json.traverse(parseJson[EcBlock](_))
    } yield blockMeta
  }

  def getBlockByHash(hash: BlockHash, requestId: Int): JobResult[Option[EcBlock]] = {
    sendRequest[GetBlockByHashRequest, EcBlock](GetBlockByHashRequest(hash, requestId), NonBlockExecutionTimeout, requestId)
      .leftMap(err => ClientError(s"Error getting block by hash $hash: $err"))
  }

  def getBlockByHashJson(hash: BlockHash, requestId: Int): JobResult[Option[JsObject]] = {
    sendRequest[GetBlockByHashRequest, JsObject](GetBlockByHashRequest(hash, requestId), NonBlockExecutionTimeout, requestId)
      .leftMap(err => ClientError(s"Error getting block json by hash $hash: $err"))
  }

  def getLastExecutionBlock(requestId: Int): JobResult[EcBlock] = for {
    lastEcBlockOpt <- getBlockByNumber(BlockNumber.Latest, requestId)
    lastEcBlock    <- Either.fromOption(lastEcBlockOpt, ClientError("Impossible: EC doesn't have blocks"))
  } yield lastEcBlock

  def blockExists(hash: BlockHash, requestId: Int): JobResult[Boolean] =
    getBlockByHash(hash, requestId).map(_.isDefined)

  override def simulate(blockStateCalls: Seq[BlockStateCall], hash: BlockHash, requestId: Int): JobResult[Seq[JsObject]] =
    sendRequest[SimulateRequest, Seq[JsObject]](SimulateRequest(blockStateCalls, hash, requestId), NonBlockExecutionTimeout, requestId)
      .flatMap(_.toRight("Simulated block was empty"))
      .leftMap(err => ClientError(s"Error simulating block: $err"))

  private def getBlockByNumberJson(number: String, requestId: Int): JobResult[Option[JsObject]] = {
    sendRequest[GetBlockByNumberRequest, JsObject](GetBlockByNumberRequest(number, requestId), NonBlockExecutionTimeout, requestId)
      .leftMap(err => ClientError(s"Error getting block by number $number: $err"))
  }

  override def getLogs(hash: BlockHash, addresses: List[EthAddress], topics: List[String], requestId: Int): JobResult[List[GetLogsResponseEntry]] =
    sendRequest[GetLogsRequest, List[GetLogsResponseEntry]](
      GetLogsRequest(hash, addresses, topics, requestId),
      NonBlockExecutionTimeout,
      requestId
    )
      .leftMap(err => ClientError(s"Error getting block logs by hash $hash: $err"))
      .map(_.getOrElse(List.empty))

  private def sendEngineRequest[A: Writes, B: Reads](request: A, timeout: FiniteDuration, requestId: Int): JobResult[B] = {
    sendRequest(request, timeout, requestId) match {
      case Right(response) => response.toRight(ClientError(s"Unexpected engine API empty response"))
      case Left(err)       => Left(ClientError(s"Engine API request error: $err"))
    }
  }
}

object HttpEngineApiClient {
  private val BlockExecutionTimeout    = 8.seconds
  private val NonBlockExecutionTimeout = 1.second
}
