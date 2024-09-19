package units.client

import cats.syntax.either.*
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.state.Blockchain
import com.wavesplatform.utils.ScorexLogging
import monix.execution.atomic.{Atomic, AtomicInt}
import play.api.libs.json.JsObject
import units.client.TestEcClients.*
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.client.engine.{EngineApiClient, LoggedEngineApiClient}
import units.collections.ListOps.*
import units.eth.EthAddress
import units.{BlockHash, JobResult, NetworkBlock}

class TestEcClients private (
    knownBlocks: Atomic[Map[BlockHash, ChainId]],
    chains: Atomic[Map[ChainId, List[ExecutionPayload]]],
    currChainIdValue: AtomicInt,
    blockchain: Blockchain
) extends ScorexLogging {
  def this(genesisPayload: ExecutionPayload, blockchain: Blockchain) = this(
    knownBlocks = Atomic(Map(genesisPayload.hash -> 0)),
    chains = Atomic(Map(0 -> List(genesisPayload))),
    currChainIdValue = AtomicInt(0),
    blockchain = blockchain
  )

  private def currChainId: ChainId = currChainIdValue.get()

  def addKnown(payload: ExecutionPayload): ExecutionPayload = {
    knownBlocks.transform(_.updated(payload.hash, currChainId))
    prependToCurrentChain(payload)
    payload
  }

  private def prependToCurrentChain(payload: ExecutionPayload): Unit =
    prependToChain(currChainId, payload)

  private def prependToChain(chainId: ChainId, payload: ExecutionPayload): Unit =
    chains.transform { chains =>
      chains.updated(chainId, payload :: chains(chainId))
    }

  private def currChain: List[ExecutionPayload] =
    chains.get().getOrElse(currChainId, throw new RuntimeException(s"Unknown chain $currChainId"))

  private val forgingBlocks = Atomic(List.empty[ForgingBlock])
  def willForge(payload: ExecutionPayload): Unit =
    forgingBlocks.transform(ForgingBlock(payload) :: _)

  private val logs = Atomic(Map.empty[GetLogsRequest, List[GetLogsResponseEntry]])
  def setBlockLogs(hash: BlockHash, address: EthAddress, topic: String, blockLogs: List[GetLogsResponseEntry]): Unit = {
    val request = GetLogsRequest(hash, address, List(topic))
    logs.transform(_.updated(request, blockLogs))
  }

  private val getLogsCalls = Atomic(Set.empty[BlockHash])

  /** Were logs of block requested during the full validation?
    */
  def fullValidatedBlocks: Set[BlockHash] = getLogsCalls.get()

  val engineApi = new LoggedEngineApiClient(
    new EngineApiClient {
      override def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash): JobResult[PayloadStatus] = {
        knownBlocks.get().get(blockHash) match {
          case Some(cid) =>
            currChainIdValue.set(cid)
            chains.transform { chains =>
              chains.updated(cid, chains(cid).dropWhile(_.hash != blockHash))
            }
            log.debug(s"Curr chain: ${currChain.map(_.hash).mkString(" <- ")}")
            PayloadStatus.Valid
          case None =>
            log.warn(s"Can't find a block $blockHash during forkChoiceUpdate call")
            PayloadStatus.Unexpected("INVALID") // Generally this is wrong, but enough for now
        }
      }.asRight

      override def forkChoiceUpdateWithPayloadId(
          lastBlockHash: BlockHash,
          finalizedBlockHash: BlockHash,
          unixEpochSeconds: Long,
          suggestedFeeRecipient: EthAddress,
          prevRandao: String,
          withdrawals: Vector[Withdrawal]
      ): JobResult[PayloadId] =
        forgingBlocks
          .get()
          .collectFirst { case fb if fb.testPayload.parentHash == lastBlockHash => fb } match {
          case None =>
            throw new RuntimeException(
              s"Can't find a suitable block among: ${forgingBlocks.get().map(_.testPayload.hash).mkString(", ")}. Call willForge"
            )
          case Some(fb) =>
            fb.payloadId.asRight
        }

      override def getPayloadJson(payloadId: PayloadId): JobResult[JsObject] =
        forgingBlocks.transformAndExtract(_.withoutFirst { fb => fb.payloadId == payloadId }) match {
          case Some(fb) => TestPayloads.toPayloadJson(fb.testPayload, fb.testPayload.prevRandao).asRight
          case None =>
            throw new RuntimeException(
              s"Can't find payload $payloadId among: ${forgingBlocks.get().map(_.testPayload.hash).mkString(", ")}. Call willForge"
            )
        }

      override def applyNewPayload(payloadJson: JsObject): JobResult[Option[BlockHash]] = {
        val newPayload = NetworkBlock(payloadJson).explicitGet().toPayload
        knownBlocks.get().get(newPayload.parentHash) match {
          case Some(cid) =>
            val chain = chains.get()(cid)
            if (newPayload.parentHash == chain.head.hash) {
              prependToChain(cid, newPayload)
              knownBlocks.transform(_.updated(newPayload.hash, cid))
            } else { // Rollback
              log.debug(s"A rollback using ${newPayload.hash} detected")
              val newCid   = currChainIdValue.incrementAndGet()
              val newChain = newPayload :: chain.dropWhile(_.hash != newPayload.parentHash)
              chains.transform(_.updated(newCid, newChain))
              knownBlocks.transform(_.updated(newPayload.hash, newCid))
            }

          case None => throw notImplementedCase(s"Can't find a parent block ${newPayload.parentHash} for ${newPayload.hash}")
        }
        Some(newPayload.hash)
      }.asRight

      override def getPayloadBodyJsonByHash(hash: BlockHash): JobResult[Option[JsObject]] =
        notImplementedMethodJob("getPayloadBodyJsonByHash")

      override def getPayloadByNumber(number: BlockNumber): JobResult[Option[ExecutionPayload]] =
        number match {
          case BlockNumber.Latest    => currChain.headOption.asRight
          case BlockNumber.Number(n) => currChain.find(_.height == n).asRight
        }

      override def getPayloadByHash(hash: BlockHash): JobResult[Option[ExecutionPayload]] = {
        for {
          cid <- knownBlocks.get().get(hash)
          c   <- chains.get().get(cid)
          b   <- c.find(_.hash == hash)
        } yield b
      }.asRight

      override def getBlockJsonByHash(hash: BlockHash): JobResult[Option[JsObject]] =
        notImplementedMethodJob("getBlockJsonByHash")

      override def getLastPayload: JobResult[ExecutionPayload] = currChain.head.asRight

      override def getLogs(hash: BlockHash, address: EthAddress, topic: String): JobResult[List[GetLogsResponseEntry]] = {
        val request = GetLogsRequest(hash, address, List(topic))
        getLogsCalls.transform(_ + hash)
        logs.get().getOrElse(request, throw notImplementedCase("call setBlockLogs"))
      }.asRight

      override def getPayloadJsonDataByHash(hash: BlockHash): JobResult[PayloadJsonData] =
        notImplementedMethodJob("getPayloadJsonDataByHash")
    }
  )

  protected def notImplementedMethodJob[A](text: String): JobResult[A] = throw new NotImplementedMethod(text)
  protected def notImplementedCase(text: String): Throwable            = new NotImplementedCase(text)
}

object TestEcClients {
  final class NotImplementedMethod(message: String) extends RuntimeException(message)
  final class NotImplementedCase(message: String)   extends RuntimeException(message)

  private type ChainId = Int

  private case class ForgingBlock(payloadId: String, testPayload: ExecutionPayload)
  private object ForgingBlock {
    def apply(testPayload: ExecutionPayload): ForgingBlock =
      new ForgingBlock(testPayload.hash.take(16), testPayload)
  }
}
