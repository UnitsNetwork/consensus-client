package units.client

import cats.syntax.either.*
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.state.Blockchain
import com.wavesplatform.utils.ScorexLogging
import monix.execution.atomic.{Atomic, AtomicInt}
import play.api.libs.json.JsObject
import units.ELUpdater.calculateRandao
import units.client.TestEcClients.*
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.client.engine.{EngineApiClient, LoggedEngineApiClient}
import units.collections.ListOps.*
import units.eth.{EthAddress, EthereumConstants}
import units.{BlockHash, Job, NetworkL2Block}

import scala.collection.View
import scala.util.chaining.scalaUtilChainingOps

class TestEcClients private (
    knownBlocks: Atomic[Map[BlockHash, ChainId]],
    chains: Atomic[Map[ChainId, List[TestEcBlock]]],
    currChainIdValue: AtomicInt,
    blockchain: Blockchain
) extends ScorexLogging {
  def this(genesis: EcBlock, blockchain: Blockchain) = this(
    knownBlocks = Atomic(Map(genesis.hash -> 0)),
    chains = Atomic(Map(0 -> List(TestEcBlock(genesis, EthereumConstants.EmptyPrevRandaoHex)))),
    currChainIdValue = AtomicInt(0),
    blockchain = blockchain
  )

  private def currChainId: ChainId = currChainIdValue.get()

  def addKnown(ecBlock: EcBlock, epochNumber: Int = blockchain.height): TestEcBlock = {
    knownBlocks.transform(_.updated(ecBlock.hash, currChainId))
    mkTestEcBlock(ecBlock, epochNumber).tap(prependToCurrentChain)
  }

  private def mkTestEcBlock(ecBlock: EcBlock, epochNumber: Int): TestEcBlock = TestEcBlock(
    ecBlock,
    calculateRandao(
      blockchain.vrf(epochNumber).getOrElse(throw new RuntimeException(s"VRF is empty for epoch $epochNumber")),
      ecBlock.parentHash
    )
  )

  private def prependToCurrentChain(b: TestEcBlock): Unit =
    prependToChain(currChainId, b)

  private def prependToChain(chainId: ChainId, b: TestEcBlock): Unit =
    chains.transform { chains =>
      chains.updated(chainId, b :: chains(chainId))
    }

  private def currChain: View[EcBlock] =
    chains.get().getOrElse(currChainId, throw new RuntimeException(s"Unknown chain $currChainId")).view.map(_.ecBlock)

  private val forgingBlocks = Atomic(List.empty[ForgingBlock])
  def willForge(ecBlock: EcBlock, epochNumber: Int = blockchain.height): Unit =
    forgingBlocks.transform(ForgingBlock(mkTestEcBlock(ecBlock, epochNumber)) :: _)

  private val logs = Atomic(Map.empty[GetLogsRequest, List[GetLogsResponseEntry]])
  def setBlockLogs(hash: BlockHash, topic: String, blockLogs: List[GetLogsResponseEntry]): Unit = {
    val request = GetLogsRequest(hash, List(topic))
    logs.transform(_.updated(request, blockLogs))
  }

  private val getLogsCalls = Atomic(Set.empty[BlockHash])

  /** Were logs of block requested during the full validation?
    */
  def fullValidatedBlocks: Set[BlockHash] = getLogsCalls.get()

  val engineApi = LoggedEngineApiClient {
    new EngineApiClient {
      override def forkChoiceUpdate(blockHash: BlockHash, finalizedBlockHash: BlockHash): Job[String] = {
        knownBlocks.get().get(blockHash) match {
          case Some(cid) =>
            currChainIdValue.set(cid)
            chains.transform { chains =>
              chains.updated(cid, chains(cid).dropWhile(_.ecBlock.hash != blockHash))
            }
            log.debug(s"Curr chain: ${currChain.map(_.hash).mkString(" <- ")}")
            "VALID"
          case None =>
            log.warn(s"Can't find a block $blockHash during forkChoiceUpdate call")
            "INVALID" // Generally this is wrong, but enough for now
        }
      }.asRight

      override def forkChoiceUpdateWithPayloadId(
          lastBlockHash: BlockHash,
          finalizedBlockHash: BlockHash,
          unixEpochSeconds: Long,
          suggestedFeeRecipient: EthAddress,
          prevRandao: String,
          withdrawals: Vector[Withdrawal]
      ): Job[PayloadId] =
        forgingBlocks
          .get()
          .collectFirst { case fb if fb.testBlock.ecBlock.parentHash == lastBlockHash => fb } match {
          case None =>
            throw new RuntimeException(
              s"Can't find a suitable block among: ${forgingBlocks.get().map(_.testBlock.ecBlock.hash).mkString(", ")}. Call willForge"
            )
          case Some(fb) =>
            fb.payloadId.asRight
        }

      override def getPayload(payloadId: PayloadId): Job[JsObject] =
        forgingBlocks.transformAndExtract(_.withoutFirst { fb => fb.payloadId == payloadId }) match {
          case Some(fb) => TestEcBlocks.toPayload(fb.testBlock.ecBlock, fb.testBlock.prevRandao).asRight
          case None =>
            throw new RuntimeException(
              s"Can't find payload $payloadId among: ${forgingBlocks.get().map(_.testBlock.ecBlock.hash).mkString(", ")}. Call willForge"
            )
        }

      override def applyNewPayload(payload: JsObject): Job[Option[BlockHash]] = {
        val newBlock     = NetworkL2Block(payload).explicitGet().toEcBlock
        val newTestBlock = TestEcBlock(newBlock, (payload \ "prevRandao").as[String])
        knownBlocks.get().get(newBlock.parentHash) match {
          case Some(cid) =>
            val chain = chains.get()(cid)
            if (newBlock.parentHash == chain.head.ecBlock.hash) {
              prependToChain(cid, newTestBlock)
              knownBlocks.transform(_.updated(newBlock.hash, cid))
            } else { // Rollback
              log.debug(s"A rollback using ${newBlock.hash} detected")
              val newCid   = currChainIdValue.incrementAndGet()
              val newChain = newTestBlock :: chain.dropWhile(_.ecBlock.hash != newBlock.parentHash)
              chains.transform(_.updated(newCid, newChain))
              knownBlocks.transform(_.updated(newBlock.hash, newCid))
            }

          case None => throw notImplementedCase(s"Can't find a parent block ${newBlock.parentHash} for ${newBlock.hash}")
        }
        Some(newBlock.hash)
      }.asRight

      override def getPayloadBodyByHash(hash: BlockHash): Job[Option[JsObject]] =
        getBlockByHashJson(hash)

      override def getBlockByNumber(number: BlockNumber): Job[Option[EcBlock]] =
        number match {
          case BlockNumber.Latest    => currChain.headOption.asRight
          case BlockNumber.Number(n) => currChain.find(_.height == n).asRight
        }

      override def getBlockByHash(hash: BlockHash): Job[Option[EcBlock]] = {
        for {
          cid <- knownBlocks.get().get(hash)
          c   <- chains.get().get(cid)
          b   <- c.find(_.ecBlock.hash == hash)
        } yield b.ecBlock
      }.asRight

      override def getBlockByHashJson(hash: BlockHash, fullTxs: Boolean): Job[Option[JsObject]] =
        notImplementedMethodJob("getBlockByHashJson")

      override def getLastExecutionBlock: Job[EcBlock] = currChain.head.asRight

      override def blockExists(hash: BlockHash): Job[Boolean] = notImplementedMethodJob("blockExists")

      override def getLogs(hash: BlockHash, topic: String): Job[List[GetLogsResponseEntry]] = {
        val request = GetLogsRequest(hash, List(topic))
        getLogsCalls.transform(_ + hash)
        logs.get().getOrElse(request, throw notImplementedCase("call setBlockLogs"))
      }.asRight
    }
  }

  protected def notImplementedMethodJob[A](text: String): Job[A] = throw new NotImplementedMethod(text)
  protected def notImplementedCase(text: String): Throwable      = new NotImplementedCase(text)
}

object TestEcClients {
  final class NotImplementedMethod(message: String) extends RuntimeException(message)
  final class NotImplementedCase(message: String)   extends RuntimeException(message)

  private type ChainId = Int

  private case class ForgingBlock(payloadId: String, testBlock: TestEcBlock)
  private object ForgingBlock {
    def apply(testBlock: TestEcBlock): ForgingBlock =
      new ForgingBlock(testBlock.ecBlock.hash.take(16), testBlock)
  }

  case class TestEcBlock(ecBlock: EcBlock, prevRandao: String)
}
