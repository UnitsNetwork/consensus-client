package units.client.engine

import play.api.libs.json.*
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.eth.EthAddress
import units.{BlockHash, JobResult}

trait EngineApiClient {
  def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash): JobResult[String] // TODO Replace String with an appropriate type

  def forkChoiceUpdateWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal] = Vector.empty
  ): JobResult[PayloadId]

  def getPayload(payloadId: PayloadId): JobResult[JsObject]

  def applyNewPayload(payload: JsObject): JobResult[Option[BlockHash]]

  def getPayloadBodyByHash(hash: BlockHash): JobResult[Option[JsObject]]

  def getBlockByNumber(number: BlockNumber): JobResult[Option[EcBlock]]

  def getBlockByHash(hash: BlockHash): JobResult[Option[EcBlock]]

  def getBlockByHashJson(hash: BlockHash): JobResult[Option[JsObject]]

  def getLastExecutionBlock: JobResult[EcBlock]

  def blockExists(hash: BlockHash): JobResult[Boolean]

  def getLogs(hash: BlockHash, address: EthAddress, topic: String): JobResult[List[GetLogsResponseEntry]]
}

object EngineApiClient {
  type PayloadId = String
}
