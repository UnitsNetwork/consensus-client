package units.client.engine

import com.wavesplatform.utils.LoggerFacade
import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.LoggedEngineApiClient.excludedJsonFields
import units.client.engine.model.*
import units.eth.EthAddress
import units.{BlockHash, JobResult}

import java.util.concurrent.ThreadLocalRandom
import scala.util.chaining.scalaUtilChainingOps

class LoggedEngineApiClient(underlying: EngineApiClient) extends EngineApiClient {
  protected val log: LoggerFacade = LoggerFacade(LoggerFactory.getLogger(underlying.getClass))

  override def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash): JobResult[PayloadStatus] =
    wrap(s"forkChoiceUpdate($blockHash, f=$finalizedBlockHash)", underlying.forkChoiceUpdate(blockHash, finalizedBlockHash))

  override def forkChoiceUpdateWithPayloadId(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal]
  ): JobResult[PayloadId] = wrap(
    s"forkChoiceUpdateWithPayloadId(l=$lastBlockHash, f=$finalizedBlockHash, ts=$unixEpochSeconds, m=$suggestedFeeRecipient, " +
      s"r=$prevRandao, w={${withdrawals.mkString(", ")}}",
    underlying.forkChoiceUpdateWithPayloadId(lastBlockHash, finalizedBlockHash, unixEpochSeconds, suggestedFeeRecipient, prevRandao, withdrawals)
  )

  override def getPayload(payloadId: PayloadId): JobResult[JsObject] =
    wrap(s"getPayload($payloadId)", underlying.getPayload(payloadId), filteredJson)

  override def applyNewPayload(payload: JsObject): JobResult[Option[BlockHash]] =
    wrap(s"applyNewPayload(${filteredJson(payload)})", underlying.applyNewPayload(payload), _.fold("None")(_.toString))

  override def getPayloadBodyByHash(hash: BlockHash): JobResult[Option[JsObject]] =
    wrap(s"getPayloadBodyByHash($hash)", underlying.getPayloadBodyByHash(hash), _.fold("None")(filteredJson))

  override def getBlockByNumber(number: BlockNumber): JobResult[Option[EcBlock]] =
    wrap(s"getBlockByNumber($number)", underlying.getBlockByNumber(number), _.fold("None")(_.toString))

  override def getBlockByHash(hash: BlockHash): JobResult[Option[EcBlock]] =
    wrap(s"getBlockByHash($hash)", underlying.getBlockByHash(hash), _.fold("None")(_.toString))

  override def getBlockByHashJson(hash: BlockHash): JobResult[Option[JsObject]] =
    wrap(s"getBlockByHashJson($hash)", underlying.getBlockByHashJson(hash), _.fold("None")(filteredJson))

  override def getLastExecutionBlock: JobResult[EcBlock] =
    wrap("getLastExecutionBlock", underlying.getLastExecutionBlock)

  override def blockExists(hash: BlockHash): JobResult[Boolean] =
    wrap(s"blockExists($hash)", underlying.blockExists(hash))

  override def getLogs(hash: BlockHash, address: EthAddress, topic: String): JobResult[List[GetLogsResponseEntry]] =
    wrap(s"getLogs($hash, a=$address, t=$topic)", underlying.getLogs(hash, address, topic), _.view.map(_.data).mkString("{", ", ", "}"))

  protected def wrap[R](method: String, f: => JobResult[R], toMsg: R => String = (_: R).toString): JobResult[R] = {
    val currRequestId = ThreadLocalRandom.current().nextInt(10000, 100000).toString
    log.debug(s"[$currRequestId] $method")

    f.tap {
      case Left(e)  => log.debug(s"[$currRequestId] Error: ${e.message}")
      case Right(r) => log.debug(s"[$currRequestId] Success: ${toMsg(r)}")
    }
  }

  private def filteredJson(jsObject: JsObject): String = JsObject(
    jsObject.fields.filterNot { case (k, _) => excludedJsonFields.contains(k) }
  ).toString()
}

object LoggedEngineApiClient {
  private val excludedJsonFields =
    Set("transactions", "logsBloom", "stateRoot", "gasLimit", "gasUsed", "baseFeePerGas", "excessBlobGas")
}
