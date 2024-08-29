package units.client.engine

import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.Withdrawal
import units.eth.EthAddress
import units.{BlockHash, HasJobLogging, Job}
import play.api.libs.json.JsObject

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
}

object LoggedEngineApiClient {
  def apply(underlying: EngineApiClient): EngineApiClient = new LoggedEngineApiClient(underlying)
}
