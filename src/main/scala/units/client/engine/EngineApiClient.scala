package units.client.engine

import play.api.libs.json.*
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.eth.EthAddress
import units.{BlockHash, JobResult}

trait EngineApiClient {
  def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash): JobResult[PayloadStatus]

  def forkChoiceUpdateWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal] = Vector.empty
  ): JobResult[PayloadId]

  def getPayloadJson(payloadId: PayloadId): JobResult[JsObject]

  def applyNewPayload(payloadJson: JsObject): JobResult[Option[BlockHash]]

  def getPayloadBodyJsonByHash(hash: BlockHash): JobResult[Option[JsObject]]

  def getPayloadByNumber(number: BlockNumber): JobResult[Option[ExecutionPayload]]

  def getPayloadByHash(hash: BlockHash): JobResult[Option[ExecutionPayload]]

  def getLastPayload: JobResult[ExecutionPayload]

  def getBlockJsonByHash(hash: BlockHash): JobResult[Option[JsObject]]

  def getPayloadJsonDataByHash(hash: BlockHash): JobResult[PayloadJsonData]

  def getLogs(hash: BlockHash, address: EthAddress, topic: String): JobResult[List[GetLogsResponseEntry]]
}

object EngineApiClient {
  type PayloadId = String
}
