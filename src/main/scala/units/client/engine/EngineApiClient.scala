package units.client.engine

import play.api.libs.json.*
import units.client.JsonRpcClient.newRequestId
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.eth.EthAddress
import units.{BlockHash, JobResult}

trait EngineApiClient {
  def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash, requestId: Int = newRequestId): JobResult[PayloadStatus]

  def forkChoiceUpdateWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal] = Vector.empty,
      requestId: Int = newRequestId
  ): JobResult[PayloadId]

  def getPayload(payloadId: PayloadId, requestId: Int = newRequestId): JobResult[JsObject]

  def applyNewPayload(payload: JsObject, requestId: Int = newRequestId): JobResult[Option[BlockHash]]

  def getPayloadBodyByHash(hash: BlockHash, requestId: Int = newRequestId): JobResult[Option[JsObject]]

  def getBlockByNumber(number: BlockNumber, requestId: Int = newRequestId): JobResult[Option[EcBlock]]

  def getBlockByHash(hash: BlockHash, requestId: Int = newRequestId): JobResult[Option[EcBlock]]

  def getBlockByHashJson(hash: BlockHash, requestId: Int = newRequestId): JobResult[Option[JsObject]]

  def getLastExecutionBlock(requestId: Int = newRequestId): JobResult[EcBlock]

  def blockExists(hash: BlockHash, requestId: Int = newRequestId): JobResult[Boolean]

  def getLogs(hash: BlockHash, address: EthAddress, topic: String, requestId: Int = newRequestId): JobResult[List[GetLogsResponseEntry]]

  def onRetry(requestId: Int): Unit = {}
}

object EngineApiClient {
  type PayloadId = String
}
