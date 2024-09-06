package units.client.engine

import com.wavesplatform.utils.LoggerFacade
import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.LoggedEngineApiClient.excludedJsonFields
import units.client.engine.model.*
import units.eth.EthAddress
import units.{BlockHash, Job}

import java.util.concurrent.ThreadLocalRandom
import scala.util.chaining.scalaUtilChainingOps

class LoggedEngineApiClient(underlying: EngineApiClient) extends EngineApiClient {
  protected val log = LoggerFacade(LoggerFactory.getLogger(underlying.getClass))

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
    s"forkChoiceUpdateWithPayloadId(l=$lastBlockHash, f=$finalizedBlockHash, ts=$unixEpochSeconds, m=$suggestedFeeRecipient, " +
      s"r=$prevRandao, w={${withdrawals.mkString(", ")}}",
    underlying.forkChoiceUpdateWithPayloadId(lastBlockHash, finalizedBlockHash, unixEpochSeconds, suggestedFeeRecipient, prevRandao, withdrawals)
  )

  override def getPayload(payloadId: PayloadId): Job[JsObject] =
    wrap(s"getPayload($payloadId)", underlying.getPayload(payloadId), filteredJson)

  override def applyNewPayload(payload: JsObject): Job[Option[BlockHash]] =
    wrap(s"applyNewPayload(${filteredJson(payload)})", underlying.applyNewPayload(payload), _.fold("None")(_.toString))

  override def getPayloadBodyByHash(hash: BlockHash): Job[Option[JsObject]] =
    wrap(s"getPayloadBodyByHash($hash)", underlying.getPayloadBodyByHash(hash), _.fold("None")(filteredJson))

  override def getBlockByNumber(number: BlockNumber): Job[Option[EcBlock]] =
    wrap(s"getBlockByNumber($number)", underlying.getBlockByNumber(number), _.fold("None")(_.toString))

  override def getBlockByHash(hash: BlockHash): Job[Option[EcBlock]] =
    wrap(s"getBlockByHash($hash)", underlying.getBlockByHash(hash), _.fold("None")(_.toString))

  override def getBlockByHashJson(hash: BlockHash): Job[Option[JsObject]] =
    wrap(s"getBlockByHashJson($hash)", underlying.getBlockByHashJson(hash), _.fold("None")(filteredJson))

  override def getLastExecutionBlock: Job[EcBlock] =
    wrap("getLastExecutionBlock", underlying.getLastExecutionBlock)

  override def blockExists(hash: BlockHash): Job[Boolean] =
    wrap(s"blockExists($hash)", underlying.blockExists(hash))

  override def getLogs(hash: BlockHash, address: EthAddress, topic: String): Job[List[GetLogsResponseEntry]] =
    wrap(s"getLogs($hash, a=$address, t=$topic)", underlying.getLogs(hash, address, topic), _.mkString("{", ", ", "}"))

  protected def wrap[R](method: String, f: => Job[R], toMsg: R => String = (_: R).toString): Job[R] = {
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
