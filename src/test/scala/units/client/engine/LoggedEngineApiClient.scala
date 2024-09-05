package units.client.engine

import play.api.libs.json.JsObject
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.eth.EthAddress
import units.{BlockHash, HasJobLogging, Job}

class LoggedEngineApiClient(underlying: EngineApiClient) extends EngineApiClient with HasJobLogging {
  override def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash): Job[String] =
    wrap(s"forkChoiceUpdate($blockHash, f=$finalizedBlockHash)", underlying.forkChoiceUpdate(blockHash, finalizedBlockHash))

  override def forkChoiceUpdateWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal]
  ): Job[PayloadId] = wrap(
    s"forkChoiceUpdateWithPayloadId(lastBlockHash=$lastBlockHash, f=$finalizedBlockHash, ts=$unixEpochSeconds, feeRecipient=$suggestedFeeRecipient, " +
      s"prd=$prevRandao, w={${withdrawals.mkString(", ")}}",
    underlying.forkChoiceUpdateWithPayloadId(lastBlockHash, finalizedBlockHash, unixEpochSeconds, suggestedFeeRecipient, prevRandao, withdrawals)
  )

  override def getPayload(payloadId: PayloadId): Job[JsObject] = wrap(s"getPayload($payloadId)", underlying.getPayload(payloadId))

  override def applyNewPayload(payload: JsObject): Job[Option[BlockHash]] = wrap(s"applyNewPayload($payload)", underlying.applyNewPayload(payload))

  override def getPayloadBodyByHash(hash: BlockHash): Job[Option[JsObject]] =
    wrap(s"getPayloadBodyByHash($hash)", underlying.getPayloadBodyByHash(hash))

  override def getBlockByNumber(number: BlockNumber): Job[Option[EcBlock]] = wrap(s"getBlockByNumber($number)", underlying.getBlockByNumber(number))

  override def getBlockByHash(hash: BlockHash): Job[Option[EcBlock]] = wrap(s"getBlockByHash($hash)", underlying.getBlockByHash(hash))

  override def getBlockByHashJson(hash: BlockHash, fullTxs: Boolean): Job[Option[JsObject]] =
    wrap(s"getBlockByHashJson($hash, fullTxs=$fullTxs)", underlying.getBlockByHashJson(hash, fullTxs))

  override def getLastExecutionBlock: Job[EcBlock] = wrap("getLastExecutionBlock", underlying.getLastExecutionBlock)

  override def blockExists(hash: BlockHash): Job[Boolean] = wrap(s"blockExists($hash)", underlying.blockExists(hash))

  override def getLogs(hash: BlockHash, topic: String): Job[List[GetLogsResponseEntry]] =
    wrap(s"getLogs($hash, $topic)", underlying.getLogs(hash, topic))
}

object LoggedEngineApiClient {
  def apply(underlying: EngineApiClient): EngineApiClient = new LoggedEngineApiClient(underlying)
}
