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

  override def forkchoiceUpdated(blockHash: BlockHash, finalizedBlockHash: BlockHash, requestId: Int): JobResult[PayloadStatus] =
    wrap(requestId, s"forkchoiceUpdated($blockHash, f=$finalizedBlockHash)", underlying.forkchoiceUpdated(blockHash, finalizedBlockHash, _))

  override def forkchoiceUpdatedWithPayload(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal],
      transactions: Vector[String],
      requestId: Int
  ): JobResult[PayloadId] = wrap(
    requestId,
    s"forkchoiceUpdatedWithPayloadId(l=$lastBlockHash, f=$finalizedBlockHash, ts=$unixEpochSeconds, m=$suggestedFeeRecipient, " +
      s"r=$prevRandao, w={${withdrawals.mkString(", ")}}, t={${transactions.mkString(", ")}})",
    underlying.forkchoiceUpdatedWithPayload(
      lastBlockHash,
      finalizedBlockHash,
      unixEpochSeconds,
      suggestedFeeRecipient,
      prevRandao,
      withdrawals,
      transactions,
      _
    )
  )

  override def getPayload(payloadId: PayloadId, requestId: Int): JobResult[JsObject] =
    wrap(requestId, s"getPayload($payloadId)", underlying.getPayload(payloadId, _), filteredJson)

  override def newPayload(payload: JsObject, requestId: Int): JobResult[Option[BlockHash]] =
    wrap(requestId, s"newPayload(${filteredJson(payload)})", underlying.newPayload(payload, _), _.fold("None")(_.toString))

  override def getPayloadBodyByHash(hash: BlockHash, requestId: Int): JobResult[Option[JsObject]] =
    wrap(requestId, s"getPayloadBodyByHash($hash)", underlying.getPayloadBodyByHash(hash, _), _.fold("None")(filteredJson))

  override def getBlockByNumber(number: BlockNumber, requestId: Int): JobResult[Option[EcBlock]] =
    wrap(requestId, s"getBlockByNumber($number)", underlying.getBlockByNumber(number, _), _.fold("None")(_.toString))

  override def getBlockByHash(hash: BlockHash, requestId: Int): JobResult[Option[EcBlock]] =
    wrap(requestId, s"getBlockByHash($hash)", underlying.getBlockByHash(hash, _), _.fold("None")(_.toString))

  override def simulate(blockStateCalls: Seq[BlockStateCall], hash: BlockHash, requestId: Int): JobResult[Seq[JsObject]] =
    wrap(requestId, s"simulate($blockStateCalls,$hash)", underlying.simulate(blockStateCalls, hash, _))

  override def getBlockByHashJson(hash: BlockHash, requestId: Int): JobResult[Option[JsObject]] =
    wrap(requestId, s"getBlockByHashJson($hash)", underlying.getBlockByHashJson(hash, _), _.fold("None")(filteredJson))

  override def getLastExecutionBlock(requestId: Int): JobResult[EcBlock] =
    wrap(requestId, "getLastExecutionBlock", underlying.getLastExecutionBlock)

  override def blockExists(hash: BlockHash, requestId: Int): JobResult[Boolean] =
    wrap(requestId, s"blockExists($hash)", underlying.blockExists(hash, _))

  override def getLogs(hash: BlockHash, addresses: List[EthAddress], topics: List[String], requestId: Int): JobResult[List[GetLogsResponseEntry]] =
    wrap(
      requestId,
      s"getLogs($hash, a={${addresses.mkString(", ")}}, t={${topics.mkString(", ")}})",
      underlying.getLogs(hash, addresses, topics, _),
      _.view.map(x => s"${x.logIndex}, a=${x.address}, d=${x.data}, t=[${x.topics.mkString(",")}]").mkString("{", ", ", "}")
    )

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
    Set.empty[String] //("transactions", "logsBloom", "stateRoot", "gasLimit", "gasUsed", "baseFeePerGas", "excessBlobGas")
}
