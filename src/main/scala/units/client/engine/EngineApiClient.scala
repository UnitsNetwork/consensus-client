package units.client.engine

import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.eth.EthAddress
import units.{BlockHash, Job}
import play.api.libs.json.*

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
}

object EngineApiClient {
  type PayloadId = String
}
