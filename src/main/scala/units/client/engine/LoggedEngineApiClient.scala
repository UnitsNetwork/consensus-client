package units.client.engine

import com.wavesplatform.utils.LoggerFacade
import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.LoggedEngineApiClient.excludedJsonFields
import units.client.engine.model.*
import units.eth.EthAddress
import units.{BlockHash, JobResult}

import scala.util.chaining.scalaUtilChainingOps

class LoggedEngineApiClient(underlying: EngineApiClient) extends EngineApiClient {
  protected val log: LoggerFacade = LoggerFacade(LoggerFactory.getLogger(underlying.getClass))

  override def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash, requestId: Int): JobResult[PayloadStatus] =
    wrap(requestId, s"forkChoiceUpdate($blockHash, f=$finalizedBlockHash)", underlying.forkChoiceUpdate(blockHash, finalizedBlockHash, _))

  override def forkChoiceUpdateWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal],
      requestId: Int
  ): JobResult[PayloadId] = wrap(
    requestId,
    s"forkChoiceUpdateWithPayloadId(l=$lastBlockHash, f=$finalizedBlockHash, ts=$unixEpochSeconds, m=$suggestedFeeRecipient, " +
      s"r=$prevRandao, w={${withdrawals.mkString(", ")}}",
    underlying.forkChoiceUpdateWithPayloadId(lastBlockHash, finalizedBlockHash, unixEpochSeconds, suggestedFeeRecipient, prevRandao, withdrawals, _)
  )

  override def getPayload(payloadId: PayloadId, requestId: Int): JobResult[JsObject] =
    wrap(requestId, s"getPayload($payloadId)", underlying.getPayload(payloadId, _), filteredJson)

  override def applyNewPayload(payload: JsObject, requestId: Int): JobResult[Option[BlockHash]] =
    wrap(requestId, s"applyNewPayload(${filteredJson(payload)})", underlying.applyNewPayload(payload, _), _.fold("None")(_.toString))

  override def getPayloadBodyByHash(hash: BlockHash, requestId: Int): JobResult[Option[JsObject]] =
    wrap(requestId, s"getPayloadBodyByHash($hash)", underlying.getPayloadBodyByHash(hash, _), _.fold("None")(filteredJson))

  override def getBlockByNumber(number: BlockNumber, requestId: Int): JobResult[Option[EcBlock]] =
    wrap(requestId, s"getBlockByNumber($number)", underlying.getBlockByNumber(number, _), _.fold("None")(_.toString))

  override def getBlockByHash(hash: BlockHash, requestId: Int): JobResult[Option[EcBlock]] =
    wrap(requestId, s"getBlockByHash($hash)", underlying.getBlockByHash(hash, _), _.fold("None")(_.toString))

  override def getBlockByHashJson(hash: BlockHash, requestId: Int): JobResult[Option[JsObject]] =
    wrap(requestId, s"getBlockByHashJson($hash)", underlying.getBlockByHashJson(hash, _), _.fold("None")(filteredJson))

  override def getLastExecutionBlock(requestId: Int): JobResult[EcBlock] =
    wrap(requestId, "getLastExecutionBlock", underlying.getLastExecutionBlock)

  override def blockExists(hash: BlockHash, requestId: Int): JobResult[Boolean] =
    wrap(requestId, s"blockExists($hash)", underlying.blockExists(hash, _))

  override def getLogs(hash: BlockHash, address: EthAddress, topic: String, requestId: Int): JobResult[List[GetLogsResponseEntry]] =
    wrap(requestId, s"getLogs($hash, a=$address, t=$topic)", underlying.getLogs(hash, address, topic, _), _.view.map(_.data).mkString("{", ", ", "}"))

  override def onRetry(requestId: Int): Unit = {
    underlying.onRetry(requestId)
    logDebug(requestId, "Retry")
  }

  protected def wrap[R](requestId: Int, method: String, f: Int => JobResult[R], toMsg: R => String = (_: R).toString): JobResult[R] = {
    logDebug(requestId, method)

    f(requestId).tap {
      case Left(e)  => logDebug(requestId, s"Error: ${e.message}")
      case Right(r) => logDebug(requestId, s"Success: ${toMsg(r)}")
    }
  }

  protected def logDebug(requestId: Int, message: String): Unit = log.debug(s"[$requestId] $message")

  private def filteredJson(jsObject: JsObject): String = JsObject(
    jsObject.fields.filterNot { case (k, _) => excludedJsonFields.contains(k) }
  ).toString()
}

object LoggedEngineApiClient {
  private val excludedJsonFields =
    Set("transactions", "logsBloom", "stateRoot", "gasLimit", "gasUsed", "baseFeePerGas", "excessBlobGas")
}
