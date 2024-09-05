package units.client.engine

import play.api.libs.json.*
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.eth.EthAddress
import units.{BlockHash, Job}

trait EngineApiClient {
  def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash): Job[String] // TODO Replace String with an appropriate type

  def forkChoiceUpdateWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal] = Vector.empty
  ): Job[PayloadId]

  def getPayload(payloadId: PayloadId): Job[JsObject]

  def applyNewPayload(payload: JsObject): Job[Option[BlockHash]]

  def getPayloadBodyByHash(hash: BlockHash): Job[Option[JsObject]]

  def getBlockByNumber(number: BlockNumber): Job[Option[EcBlock]]

  def getBlockByHash(hash: BlockHash): Job[Option[EcBlock]]

  def getBlockByHashJson(hash: BlockHash, fullTxs: Boolean = false): Job[Option[JsObject]]

  def getLastExecutionBlock: Job[EcBlock]

  def blockExists(hash: BlockHash): Job[Boolean]

  def getLogs(hash: BlockHash, topic: String): Job[List[GetLogsResponseEntry]]
}

object EngineApiClient {
  type PayloadId = String
}
