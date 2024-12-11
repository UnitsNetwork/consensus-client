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
import units.{BlockHash, JobResult, NetworkL2Block}

class TestEcClients private (
    knownBlocks: Atomic[Map[BlockHash, ChainId]],
    chains: Atomic[Map[ChainId, List[EcBlock]]],
    currChainIdValue: AtomicInt,
    blockchain: Blockchain
) extends ScorexLogging {
  def this(genesis: EcBlock, blockchain: Blockchain) = this(
    knownBlocks = Atomic(Map(genesis.hash -> 0)),
    chains = Atomic(Map(0 -> List(genesis))),
    currChainIdValue = AtomicInt(0),
    blockchain = blockchain
  )

  private def currChainId: ChainId = currChainIdValue.get()

  def addKnown(ecBlock: EcBlock): EcBlock = {
    knownBlocks.transform(_.updated(ecBlock.hash, currChainId))
    prependToCurrentChain(ecBlock)
    ecBlock
  }

  private def prependToCurrentChain(b: EcBlock): Unit =
    prependToChain(currChainId, b)

  private def prependToChain(chainId: ChainId, b: EcBlock): Unit =
    chains.transform { chains =>
      chains.updated(chainId, b :: chains(chainId))
    }

  private def currChain: List[EcBlock] =
    chains.get().getOrElse(currChainId, throw new RuntimeException(s"Unknown chain $currChainId"))

  private val forgingBlocks = Atomic(List.empty[ForgingBlock])
  def willForge(ecBlock: EcBlock): Unit =
    forgingBlocks.transform(ForgingBlock(ecBlock) :: _)

  private val logs = Atomic(Map.empty[GetLogsRequest, List[GetLogsResponseEntry]])
  def setBlockLogs(hash: BlockHash, address: EthAddress, topic: String, blockLogs: List[GetLogsResponseEntry]): Unit = {
    val request = GetLogsRequest(hash, address, List(topic), 0)
    logs.transform(_.updated(request, blockLogs))
  }

  private val getLogsCalls = Atomic(Set.empty[BlockHash])

  /** Were logs of block requested during the full validation?
    */
  def fullValidatedBlocks: Set[BlockHash] = getLogsCalls.get()

  private val forkChoiceUpdateWithPayloadIdCalls = AtomicInt(0)
  def miningAttempts: Int                        = forkChoiceUpdateWithPayloadIdCalls.get()

  val engineApi = new LoggedEngineApiClient(
    new EngineApiClient {
      override def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash, requestId: Int): JobResult[PayloadStatus] = {
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
          withdrawals: Vector[Withdrawal],
          requestId: Int
      ): JobResult[PayloadId] = {
        forkChoiceUpdateWithPayloadIdCalls.increment()
        forgingBlocks
          .get()
          .collectFirst { case fb if fb.testBlock.parentHash == lastBlockHash => fb } match {
          case None =>
            throw new RuntimeException(
              s"Can't find a suitable block among: ${forgingBlocks.get().map(_.testBlock.hash).mkString(", ")}. Call willForge"
            )
          case Some(fb) =>
            fb.payloadId.asRight
        }
      }

      override def getPayload(payloadId: PayloadId, requestId: Int): JobResult[JsObject] =
        forgingBlocks.transformAndExtract(_.withoutFirst { fb => fb.payloadId == payloadId }) match {
          case Some(fb) => TestEcBlocks.toPayload(fb.testBlock, fb.testBlock.prevRandao).asRight
          case None =>
            throw new RuntimeException(
              s"Can't find payload $payloadId among: ${forgingBlocks.get().map(_.testBlock.hash).mkString(", ")}. Call willForge"
            )
        }

      override def applyNewPayload(payload: JsObject, requestId: Int): JobResult[Option[BlockHash]] = {
        val newBlock = NetworkL2Block(payload).explicitGet().toEcBlock
        knownBlocks.get().get(newBlock.parentHash) match {
          case Some(cid) =>
            val chain = chains.get()(cid)
            if (newBlock.parentHash == chain.head.hash) {
              prependToChain(cid, newBlock)
              knownBlocks.transform(_.updated(newBlock.hash, cid))
            } else { // Rollback
              log.debug(s"A rollback using ${newBlock.hash} detected")
              val newCid   = currChainIdValue.incrementAndGet()
              val newChain = newBlock :: chain.dropWhile(_.hash != newBlock.parentHash)
              chains.transform(_.updated(newCid, newChain))
              knownBlocks.transform(_.updated(newBlock.hash, newCid))
            }

          case None => notImplementedCase(s"Can't find a parent block ${newBlock.parentHash} for ${newBlock.hash}")
        }
        Some(newBlock.hash)
      }.asRight

      override def getPayloadBodyByHash(hash: BlockHash, requestId: Int): JobResult[Option[JsObject]] =
        getBlockByHashJson(hash)

      override def getBlockByNumber(number: BlockNumber, requestId: Int): JobResult[Option[EcBlock]] =
        number match {
          case BlockNumber.Latest    => currChain.headOption.asRight
          case BlockNumber.Number(n) => currChain.find(_.height == n).asRight
        }

      override def getBlockByHash(hash: BlockHash, requestId: Int): JobResult[Option[EcBlock]] = {
        for {
          cid <- knownBlocks.get().get(hash)
          c   <- chains.get().get(cid)
          b   <- c.find(_.hash == hash)
        } yield b
      }.asRight

      override def getBlockByHashJson(hash: BlockHash, requestId: Int): JobResult[Option[JsObject]] =
        notImplementedMethodJob("getBlockByHashJson")

      override def getLastExecutionBlock(requestId: Int): JobResult[EcBlock] = currChain.head.asRight

      override def blockExists(hash: BlockHash, requestId: Int): JobResult[Boolean] = notImplementedMethodJob("blockExists")

      override def getLogs(hash: BlockHash, address: EthAddress, topic: String, requestId: Int): JobResult[List[GetLogsResponseEntry]] = {
        val request = GetLogsRequest(hash, address, List(topic), 0) // requestId is ignored, see setBlockLogs
        getLogsCalls.transform(_ + hash)
        logs.get().getOrElse(request, notImplementedCase("call setBlockLogs"))
      }.asRight
    }
  )

  protected def notImplementedMethodJob[A](text: String): JobResult[A] = {
    log.warn(s"notImplementedMethodJob($text)")
    throw new NotImplementedMethod(text)
  }

  protected def notImplementedCase(text: String): Nothing = {
    log.warn(s"notImplementedCase($text)")
    throw new NotImplementedCase(text)
  }
}

object TestEcClients {
  final class NotImplementedMethod(message: String) extends RuntimeException(message)
  final class NotImplementedCase(message: String)   extends RuntimeException(message)

  private type ChainId = Int

  private case class ForgingBlock(payloadId: String, testBlock: EcBlock)
  private object ForgingBlock {
    def apply(testBlock: EcBlock): ForgingBlock =
      new ForgingBlock(testBlock.hash.take(16), testBlock)
  }
}
