package units.client.engine

import play.api.libs.json.*
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.eth.EthAddress
import units.BlockHash

trait EngineApiClient {
  def forkChoiceUpdated(blockHash: BlockHash, finalizedBlockHash: BlockHash): Either[String, PayloadStatus]

  def forkChoiceUpdatedWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal] = Vector.empty
  ): Either[String, PayloadId]

  def getPayload(payloadId: PayloadId): Either[String, JsObject]

  def applyNewPayload(payloadJson: JsObject): Either[String, Option[BlockHash]]

  def getPayloadBodyByHash(hash: BlockHash): Either[String, Option[JsObject]]

  def getBlockByNumber(number: BlockNumber): Either[String, Option[ExecutionPayload]]

  def getBlockByHash(hash: BlockHash): Either[String, Option[ExecutionPayload]]

  def getLatestBlock: Either[String, ExecutionPayload]

  def getBlockJsonByHash(hash: BlockHash): Either[String, Option[JsObject]]

  def getPayloadJsonDataByHash(hash: BlockHash): Either[String, PayloadJsonData]

  def getLogs(hash: BlockHash, address: EthAddress, topic: String): Either[String, List[GetLogsResponseEntry]]
}

object EngineApiClient {
  type PayloadId = String
}
