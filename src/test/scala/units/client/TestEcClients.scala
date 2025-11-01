package units.client

import cats.syntax.either.*
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.utils.{EthEncoding, ScorexLogging}
import monix.execution.atomic.{Atomic, AtomicInt}
import play.api.libs.json.{JsObject, Json}
import units.client.TestEcClients.*
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.client.engine.{EngineApiClient, LoggedEngineApiClient}
import units.eth.{EthAddress, EthereumConstants}
import units.{BlockHash, Result, NetworkL2Block}
import units.test.take

/** Life of a block:
  *   1. User calls willForge - goes to futureBlocks, willSimulate - to sumulatedBlocks.
  *   2. CC calls forkchoiceUpdatedWithPayload - moves from futureBlocks to pendingPayloads.
  *   3. CC calls newPayload - moves from pendingPayloads to readyBlocks.
  *   4. CC calls forkchoiceUpdated / forkchoiceUpdatedWithPayload - head block moves from readyBlocks to chain.
  */
class TestEcClients private (
    pendingPayloads: Atomic[Map[PayloadId, EcBlock]],
    readyBlocks: Atomic[Map[BlockHash, EcBlock]],
    chain: Atomic[List[EcBlock]]
) extends ScorexLogging {
  def this(genesis: EcBlock) = this(
    pendingPayloads = Atomic(Map.empty),
    readyBlocks = Atomic(Map.empty),
    chain = Atomic(List(genesis))
  )

  def addKnown(ecBlock: EcBlock): EcBlock = {
    chain.transform(ecBlock :: _)
    ecBlock
  }

  private val futureBlocks   = Atomic(List.empty[EcBlock])
  private val simulateBlocks = Atomic(List.empty[EcBlock])

  // Also removes all blocks in simulateBlocks, futureBlocks and pendingPayloads that have same parent
  private def appendBlock(b: EcBlock, simulated: Boolean): Unit = {
    if (simulated) {
      simulateBlocks.transform(bs => b :: bs.filterNot(_.parentHash == b.parentHash))
      futureBlocks.transform(bs => bs.filterNot(_.parentHash == b.parentHash))
    } else {
      simulateBlocks.transform(bs => bs.filterNot(_.parentHash == b.parentHash))
      futureBlocks.transform(bs => b :: bs.filterNot(_.parentHash == b.parentHash))
    }

    pendingPayloads.transform(_.filterNot { case (_, pb) => pb.parentHash == b.parentHash })
    log.debug(s"Will ${if (simulated) "simulate" else "forge"} ${b.hash.take(LogChars)}->${b.parentHash.take(LogChars)}")
  }

  def willForge(ecBlock: EcBlock): Unit    = appendBlock(ecBlock, simulated = false)
  def willSimulate(ecBlock: EcBlock): Unit = appendBlock(ecBlock, simulated = true)

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
      override def simulate(blockStateCalls: Seq[BlockStateCall], hash: BlockHash, requestId: Int): Result[Seq[JsObject]] = {
        val stateCall = blockStateCalls.headOption.getOrElse(fail("Multiple blockStateCalls"))
        require(stateCall.calls.isEmpty, s"Not implemented for nonempty calls, because of logs: ${stateCall.calls}")

        val nextFutureBlock = simulateBlocks.transformAndExtract { xs =>
          val (found, rest) = xs.partition(_.parentHash == hash)
          found match {
            case b :: Nil => (b, rest)
            case Nil =>
              val foundStr = simulateBlocks.get().map { b => s"${b.hash.take(LogChars)}->${b.parentHash.take(LogChars)}" }
              fail(s"Can't find the block with the ${hash.take(LogChars)} parent hash among: ${foundStr.mkString(", ")}. Call willSimulate")
            case _ => fail(s"Found multiple future block candidates: $found")
          }
        }

        // We don't add a pending payload, because simulate returns a ready to apply payload instead of payload id
        // pendingPayloads.transform(_.updated(nextFutureBlock.payloadId, nextFutureBlock.testBlock))

        val sb = nextFutureBlock.copy(
          parentHash = hash,
          height = stateCall.blockOverrides.number,
          timestamp = stateCall.blockOverrides.time,
          minerRewardL2Address = stateCall.blockOverrides.feeRecipient,
          baseFeePerGas = stateCall.blockOverrides.baseFeePerGas,
          prevRandao = stateCall.blockOverrides.prevRandao,
          withdrawals = stateCall.blockOverrides.withdrawals.toVector
        )

        require(
          sb.height == nextFutureBlock.height,
          s"Required height ${sb.height}, got: ${nextFutureBlock.height}"
        )
        require(
          sb.minerRewardL2Address == nextFutureBlock.minerRewardL2Address,
          s"Required minerRewardL2Address ${sb.minerRewardL2Address}, got: ${nextFutureBlock.minerRewardL2Address}"
        )

        val json = Json.obj(
          "miner"        -> sb.minerRewardL2Address,
          "number"       -> EthEncoding.toHexString(sb.height),
          "hash"         -> sb.hash,
          "mixHash"      -> sb.prevRandao,
          "parentHash"   -> sb.parentHash,
          "stateRoot"    -> sb.stateRoot,
          "receiptsRoot" -> EthereumConstants.EmptyRootHashHex,
          "logsBloom"    -> EthereumConstants.EmptyLogsBloomHex,
          "gasLimit"     -> EthEncoding.toHexString(sb.gasLimit),
          "gasUsed"      -> EthEncoding.toHexString(sb.gasUsed),
          "timestamp"    -> EthEncoding.toHexString(sb.timestamp),
          // Empty extraData
          "baseFeePerGas" -> EthEncoding.toHexString(sb.baseFeePerGas.getValue),
          "withdrawals"   -> sb.withdrawals
        )

        simulateBlocks.transform(sb :: _) // Insert an updated

        Seq(json).asRight
      }

      override def forkchoiceUpdated(blockHash: BlockHash, finalizedBlockHash: BlockHash, requestId: Int): Result[PayloadStatus] = {
        val readyBlock = readyBlocks.transformAndExtract { xs =>
          val (found, rest) = xs.partition { case (h, _) => h == blockHash } // Remove from future blocks, it's going to chain
          require(found.size <= 1, s"Found multiple blocks: ${found.keys.mkString(", ")}")
          (found.values.headOption, rest)
        }

        // Changes the head to the expected
        val updatedChain = chain.transformAndGet { chain =>
          readyBlock match {
            case Some(b) => b :: chain.dropWhile(_.hash != b.parentHash)
            case None    => chain.dropWhile(_.hash != blockHash)
          }
        }

        log.debug(s"Chain: ${updatedChain.map(_.hash.take(LogChars)).mkString(" -> ")}")
        PayloadStatus.Valid
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
      ): Result[PayloadId] = {
        forkChoiceUpdateWithPayloadIdCalls.increment()
        forkchoiceUpdated(lastBlockHash, finalizedBlockHash, requestId)

        val nextFutureBlock = futureBlocks.transformAndExtract { xs =>
          val (found, rest) = xs.partition(_.parentHash == lastBlockHash)
          found match {
            case b :: Nil => (b, rest)
            case Nil =>
              val foundForgedStr = futureBlocks.get().map { b =>
                s"${b.hash.take(LogChars)}->${b.parentHash.take(LogChars)}"
              }
              fail(
                s"Can't find the block with the ${lastBlockHash.take(LogChars)} parent hash among: ${foundForgedStr.mkString(", ")}. Call willForge"
              )
            case _ => fail(s"Found multiple future block candidates: $found")
          }
        }

        val payloadId = getPayloadId(nextFutureBlock.hash)
        pendingPayloads.transform(_.updated(payloadId, nextFutureBlock))
        payloadId.asRight
      }

      override def getPayload(payloadId: PayloadId, requestId: Int): Result[JsObject] = {
        log.debug(s"Pending payloads: ${pendingPayloads.get().values.map(b => s"${b.hash}->${b.parentHash}")}")
        pendingPayloads.get().get(payloadId) match {
          case Some(b) => TestEcBlocks.toPayload(b, b.prevRandao).asRight
          case None =>
            val pendingPayloadIdsStr = pendingPayloads.get().keys.mkString(", ")
            fail(s"Can't find a block with the $payloadId payload id among: $pendingPayloadIdsStr. Call willForge")
        }
      }

      override def newPayload(payload: JsObject, requestId: Int): Result[Option[BlockHash]] = {
        val blockHash = (payload \ "blockHash").as[BlockHash]
        val payloadId = getPayloadId(blockHash)
        val forgedBlock = pendingPayloads.transformAndExtract { xs =>
          (xs.get(payloadId), xs.removed(payloadId))
        }
        val simulatedBlock = simulateBlocks.transformAndExtract { xs =>
          val (x, rest) = xs.partition(_.hash == blockHash)
          require(x.size <= 1, s"Found multiple simulated blocks with same hash: $x")
          (x.headOption, rest)
        }

        (forgedBlock, simulatedBlock) match {
          case (Some(fb), Some(sb)) => fail(s"Found forged and simulated blocks: fb=$fb, sb=$sb")
          case (Some(fb), _)        => readyBlocks.transform(_.updated(fb.hash, fb))
          case (_, Some(sb))        => readyBlocks.transform(_.updated(sb.hash, sb))
          case _ =>
            val nb = NetworkL2Block(payload).explicitGet().toEcBlock
            log.debug(s"Neither a forged, nor a simulated block ${nb.hash.take(LogChars)}. Assume it came from network.")
            readyBlocks.transform(_.updated(nb.hash, nb))
        }
        Some(blockHash).asRight
      }

      override def getPayloadBodyByHash(hash: BlockHash, requestId: Int): Result[Option[JsObject]] =
        getBlockByHashJson(hash)

      override def getBlockByNumber(number: BlockNumber, requestId: Int): Result[Option[EcBlock]] =
        number match {
          case BlockNumber.Latest    => chain.get().headOption.asRight
          case BlockNumber.Number(n) => chain.get().find(_.height == n).asRight
        }

      override def getBlockByHash(hash: BlockHash, requestId: Int): Result[Option[EcBlock]] =
        chain.get().find(_.hash == hash).asRight

      override def getBlockByHashJson(hash: BlockHash, fullTransactionObjects: Boolean, requestId: Int): Result[Option[JsObject]] =
        Some(Json.obj()).asRight // This could fail some tests

      override def getLastExecutionBlock(requestId: Int): Result[EcBlock] = chain.get().head.asRight

      override def blockExists(hash: BlockHash, requestId: Int): Result[Boolean] = notImplementedMethodJob("blockExists")

      override def getLogs(
          hash: BlockHash,
          addresses: List[EthAddress],
          topics: List[String],
          requestId: Int
      ): Result[List[GetLogsResponseEntry]] = {
        val request = GetLogsRequest(hash, addresses, topics, 0) // requestId is ignored, see setBlockLogs
        getLogsCalls.transform(_ + hash)
        logs.get().getOrElse(request, notImplementedCase(s"call setBlockLogs with $request"))
      }.asRight
    }
  )

  protected def notImplementedMethodJob[A](text: String): Result[A] = {
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
  val LogChars = 7

  final class NotImplementedMethod(message: String) extends RuntimeException(message)
  final class NotImplementedCase(message: String)   extends RuntimeException(message)

  private type ChainId = Int

  case class BlockInChain(block: EcBlock, chainId: ChainId)

  def getPayloadId(hash: BlockHash): PayloadId = hash.take(16)
}
