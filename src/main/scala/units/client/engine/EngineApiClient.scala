package units.client.engine

import play.api.libs.json.*
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.eth.EthAddress
import units.{BlockHash, JobResult}

trait EngineApiClient {
  def forkChoiceUpdated(blockHash: BlockHash, finalizedBlockHash: BlockHash): JobResult[PayloadStatus]

  def forkChoiceUpdatedWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal] = Vector.empty
  ): JobResult[PayloadId]

  def getPayload(payloadId: PayloadId): JobResult[JsObject]

  def applyNewPayload(payloadJson: JsObject): JobResult[Option[BlockHash]]

  def getPayloadBodyByHash(hash: BlockHash): JobResult[Option[JsObject]]

  def getBlockByNumber(number: BlockNumber): JobResult[Option[ExecutionPayload]]

  def getBlockByHash(hash: BlockHash): JobResult[Option[ExecutionPayload]]

  def getLatestBlock: JobResult[ExecutionPayload]

  def getBlockJsonByHash(hash: BlockHash): JobResult[Option[JsObject]]

  def getPayloadJsonDataByHash(hash: BlockHash): JobResult[PayloadJsonData]

  def getLogs(hash: BlockHash, address: EthAddress, topic: String): JobResult[List[GetLogsResponseEntry]]
}

object EngineApiClient {
  type PayloadId = String
}
