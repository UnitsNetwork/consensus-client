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

  override def getPayloadJson(payloadId: PayloadId): JobResult[JsObject] =
    wrap(s"getPayloadJson($payloadId)", underlying.getPayloadJson(payloadId), filteredJsonStr)

  override def applyNewPayload(payloadJson: JsObject): JobResult[Option[BlockHash]] =
    wrap(s"applyNewPayload(${filteredJsonStr(payloadJson)})", underlying.applyNewPayload(payloadJson), _.fold("None")(identity))

  override def getPayloadBodyJsonByHash(hash: BlockHash): JobResult[Option[JsObject]] =
    wrap(s"getPayloadBodyJsonByHash($hash)", underlying.getPayloadBodyJsonByHash(hash), _.fold("None")(filteredJsonStr))

  override def getPayloadByNumber(number: BlockNumber): JobResult[Option[ExecutionPayload]] =
    wrap(s"getPayloadByNumber($number)", underlying.getPayloadByNumber(number), _.fold("None")(_.toString))

  override def getPayloadByHash(hash: BlockHash): JobResult[Option[ExecutionPayload]] =
    wrap(s"getPayloadByHash($hash)", underlying.getPayloadByHash(hash), _.fold("None")(_.toString))

  override def getLastPayload: JobResult[ExecutionPayload] =
    wrap("getLastPayload", underlying.getLastPayload)

  override def getBlockJsonByHash(hash: BlockHash): JobResult[Option[JsObject]] =
    wrap(s"getBlockByHashJson($hash)", underlying.getBlockJsonByHash(hash), _.fold("None")(filteredJsonStr))

  override def getPayloadJsonDataByHash(hash: BlockHash): JobResult[PayloadJsonData] = {
    wrap(
      s"getPayloadJsonDataByHash($hash)",
      underlying.getPayloadJsonDataByHash(hash),
      pjd => PayloadJsonData(filteredJson(pjd.blockJson), filteredJson(pjd.bodyJson)).toString
    )
  }

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

  private def filteredJson(jsObject: JsObject): JsObject = JsObject(
    jsObject.fields.filterNot { case (k, _) => excludedJsonFields.contains(k) }
  )

  private def filteredJsonStr(jsObject: JsObject): String =
    filteredJson(jsObject).toString
}

object LoggedEngineApiClient {
  private val excludedJsonFields =
    Set("transactions", "logsBloom", "stateRoot", "gasLimit", "gasUsed", "baseFeePerGas", "excessBlobGas")
}
