package units.client

import cats.syntax.either.*
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.state.Blockchain
import com.wavesplatform.utils.{EthEncoding, ScorexLogging}
import monix.execution.atomic.{Atomic, AtomicInt}
import play.api.libs.json.{JsObject, Json}
import units.client.TestEcClients.*
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.client.engine.{EngineApiClient, LoggedEngineApiClient}
import units.collections.ListOps.*
import units.eth.{EthAddress, EthereumConstants}
import units.{BlockHash, JobResult, NetworkL2Block}

import scala.util.chaining.*

class TestEcClients private (
    elStandardBridgeAddress: EthAddress,
    elNativeBridgeAddress: EthAddress,
    knownBlocks: Atomic[Map[BlockHash, ChainId]],
    chains: Atomic[Map[ChainId, List[EcBlock]]],
    currChainIdValue: AtomicInt,
    blockchain: Blockchain
) extends ScorexLogging {
  def this(elStandardBridgeAddress: EthAddress, elNativeBridgeAddress: EthAddress, genesis: EcBlock, blockchain: Blockchain) = this(
    elStandardBridgeAddress = elStandardBridgeAddress,
    elNativeBridgeAddress = elNativeBridgeAddress,
    knownBlocks = Atomic(Map(genesis.hash -> 0)),
    chains = Atomic(Map(0 -> List(genesis))),
    currChainIdValue = AtomicInt(0),
    blockchain = blockchain
  )

  private def currChainId: ChainId = currChainIdValue.get()

  def lastKnownBlock(chainId: ChainId = currChainId): EcBlock =
    chains
      .get()
      .getOrElse(chainId, fail(s"Can't find chain #$chainId"))
      .headOption
      .getOrElse(fail(s"No blocks in chain #$chainId"))

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
    chains.get().getOrElse(currChainId, fail(s"Unknown chain $currChainId"))

  private val generatedBlocks                               = Atomic(List.empty[GeneratedBlock])
  private def appendGeneratedBlock(b: GeneratedBlock): Unit = generatedBlocks.transform(b :: _)

  def willForge(ecBlock: EcBlock): Unit    = appendGeneratedBlock(GeneratedBlock(ecBlock))
  def willSimulate(ecBlock: EcBlock): Unit = appendGeneratedBlock(GeneratedBlock(ecBlock, simulated = true))

  private val logs = Atomic(Map.empty[GetLogsRequest, List[GetLogsResponseEntry]])
  def setBlockLogs(request: GetLogsRequest, response: List[GetLogsResponseEntry]): Unit = logs.transform(_.updated(request, response))

  private val getLogsCalls = Atomic(Set.empty[BlockHash])

  /** Were logs of block requested during the full validation?
    */
  def fullValidatedBlocks: Set[BlockHash] = getLogsCalls.get()

  private val forkChoiceUpdateWithPayloadIdCalls = AtomicInt(0)
  def miningAttempts: Int                        = forkChoiceUpdateWithPayloadIdCalls.get()

  val engineApi = new LoggedEngineApiClient(
    new EngineApiClient {
      private val LogChars = 7

      override def simulate(blockStateCalls: Seq[BlockStateCall], hash: BlockHash, requestId: Int): JobResult[Seq[JsObject]] =
        generatedBlocks.transformAndExtract(_.withoutFirst { b => b.simulated && b.testBlock.parentHash == hash }) match {
          case None =>
            val related = generatedBlocks
              .get()
              .collect { case b if b.simulated => s"${b.testBlock.hash.take(LogChars)}->${b.testBlock.parentHash.take(LogChars)}" }
              .mkString(", ")
            fail(s"Can't find the simulated block with the ${hash.take(LogChars)} parent hash among: $related. Call willSimulate")

          case Some(b) =>
            val stateCall = blockStateCalls.headOption.getOrElse(fail("Multiple blockStateCalls"))
            require(stateCall.calls.isEmpty, s"Not implemented for nonempty calls, because of logs: ${stateCall.calls}")

            val simulated = EcBlock(
              hash = b.testBlock.hash,
              parentHash = hash,
              stateRoot = EthereumConstants.EmptyRootHashHex,
              height = stateCall.blockOverrides.number,
              timestamp = stateCall.blockOverrides.time,
              minerRewardL2Address = stateCall.blockOverrides.feeRecipient,
              baseFeePerGas = stateCall.blockOverrides.baseFeePerGas,
              gasLimit = 0,
              gasUsed = 0,
              prevRandao = stateCall.blockOverrides.prevRandao,
              withdrawals = stateCall.blockOverrides.withdrawals.toVector
            )

            require(simulated.height == b.testBlock.height, s"Required height ${simulated.height}, got: ${b.testBlock.height}")
            require(
              simulated.minerRewardL2Address == b.testBlock.minerRewardL2Address,
              s"Required minerRewardL2Address ${simulated.minerRewardL2Address}, got: ${b.testBlock.minerRewardL2Address}"
            )

            val json = Json.obj(
              "miner"        -> simulated.minerRewardL2Address,
              "number"       -> EthEncoding.toHexString(simulated.height),
              "hash"         -> simulated.hash,
              "mixHash"      -> simulated.prevRandao,
              "parentHash"   -> simulated.parentHash,
              "stateRoot"    -> simulated.stateRoot,
              "receiptsRoot" -> EthereumConstants.EmptyRootHashHex,
              "logsBloom"    -> EthereumConstants.EmptyLogsBloomHex,
              "gasLimit"     -> EthEncoding.toHexString(simulated.gasLimit),
              "gasUsed"      -> EthEncoding.toHexString(simulated.gasUsed),
              "timestamp"    -> EthEncoding.toHexString(simulated.timestamp),
              // Empty extraData
              "baseFeePerGas" -> EthEncoding.toHexString(simulated.baseFeePerGas.getValue)
              // "withdrawals"   -> simulated.withdrawals
            )

            setBlockLogs(GetLogsRequest(simulated.hash, List(elStandardBridgeAddress, elNativeBridgeAddress), Nil, 0), Nil)

            Seq(json).asRight
        }

      override def forkchoiceUpdated(blockHash: BlockHash, finalizedBlockHash: BlockHash, requestId: Int): JobResult[PayloadStatus] = {
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

      override def forkchoiceUpdatedWithPayload(
          lastBlockHash: BlockHash,
          finalizedBlockHash: BlockHash,
          unixEpochSeconds: Long,
          suggestedFeeRecipient: EthAddress,
          prevRandao: String,
          withdrawals: Vector[Withdrawal],
          transactions: Vector[String],
          requestId: Int
      ): JobResult[PayloadId] = {
        forkChoiceUpdateWithPayloadIdCalls.increment()
        generatedBlocks
          .get()
          .collectFirst { case b if !b.simulated && b.testBlock.parentHash == lastBlockHash => b } match {
          case Some(b) => b.payloadId.asRight
          case None =>
            val related = generatedBlocks.get().collect {
              case b if !b.simulated => s"${b.testBlock.hash.take(LogChars)}->${b.testBlock.parentHash.take(LogChars)}"
            }
            fail(s"Can't find the block with the ${lastBlockHash.take(LogChars)} parent hash among: ${related.mkString(", ")}. Call willForge")
        }
      }

      override def getPayload(payloadId: PayloadId, requestId: Int): JobResult[JsObject] =
        generatedBlocks.transformAndExtract(_.withoutFirst { b => !b.simulated && b.payloadId == payloadId }) match {
          case Some(b) => TestEcBlocks.toPayload(b.testBlock, b.testBlock.prevRandao).asRight
          case None =>
            val related = generatedBlocks.get().collect { case b if !b.simulated => b.payloadId }
            fail(s"Can't find a block with the $payloadId payload id among: ${related.mkString(", ")}. Call willForge")
        }

      override def newPayload(payload: JsObject, requestId: Int): JobResult[Option[BlockHash]] = {
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

      override def getLogs(
          hash: BlockHash,
          addresses: List[EthAddress],
          topics: List[String],
          requestId: Int
      ): JobResult[List[GetLogsResponseEntry]] = {
        val request = GetLogsRequest(hash, addresses, topics, 0) // requestId is ignored, see setBlockLogs
        getLogsCalls.transform(_ + hash)
        logs.get().getOrElse(request, notImplementedCase(s"call setBlockLogs with $request)"))
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

  protected def fail(text: String): Nothing = throw new RuntimeException(text)
}

object TestEcClients {
  final class NotImplementedMethod(message: String) extends RuntimeException(message)
  final class NotImplementedCase(message: String)   extends RuntimeException(message)

  private type ChainId = Int

  case class GeneratedBlock(testBlock: EcBlock, simulated: Boolean = false) {
    val payloadId: String = testBlock.hash.take(16)
  }
}
