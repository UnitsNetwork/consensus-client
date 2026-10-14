package units

import com.wavesplatform.common.utils.EitherExt2.explicitGet
import org.web3j.protocol.core.DefaultBlockParameterName
import units.ELUpdater.NoFinalizedBlockHash
import units.client.engine.model.{BlockNumber, EcBlock}
import units.eth.EthAddress

/** The consensus client reports no finalized block (an all-zero hash) in forkchoiceUpdated when the new head is below the contract's finalized block.
  * Checks that op-geth accepts it, moves the head, and keeps the previous finalized block.
  *
  * Drives op-geth directly through the engine API, so only the EL node is started.
  */
class ForkchoiceNoFinalizedBlockTestSuite extends BaseDockerTestSuite {
  private val prevRandao = "0x" + "0" * 64
  private val minerA     = EthAddress.unsafeFrom("0x" + "a" * 40)
  private val minerB     = EthAddress.unsafeFrom("0x" + "b" * 40)

  /** Builds a block on top of the current head `parent` and imports it without making it the head. */
  private def buildBlock(parent: EcBlock, finalized: BlockHash, feeRecipient: EthAddress): EcBlock = {
    val payloadId = ec1.engineApi
      .forkchoiceUpdatedWithPayload(parent.hash, finalized, parent.timestamp + 1, feeRecipient, prevRandao, Vector.empty, Vector.empty)
      .explicitGet()
    val payload = ec1.engineApi.getPayload(payloadId).explicitGet()
    val hash    = ec1.engineApi.newPayload(payload).explicitGet().value
    ec1.engineApi.getBlockByHash(hash).explicitGet().value
  }

  private def head: BlockHash = ec1.engineApi.getLastExecutionBlock().explicitGet().hash

  private def finalizedHash: Option[BlockHash] = {
    val r = ec1.web3j.ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false).send()
    Option.when(!r.hasError)(r.getBlock).flatMap(Option(_)).map(b => BlockHash(b.getHash))
  }

  "forkchoiceUpdated with no finalized block" in {
    val genesis = ec1.engineApi.getBlockByNumber(BlockNumber.Number(0)).explicitGet().value

    step("Build a1 on genesis with no finalized block")
    val a1 = buildBlock(genesis, NoFinalizedBlockHash, minerA)

    step("Make a1 the head with no finalized block")
    ec1.engineApi.forkchoiceUpdated(a1.hash, NoFinalizedBlockHash).explicitGet()
    head shouldBe a1.hash
    finalizedHash shouldBe empty

    step("Build siblings a2 and b2 on a1")
    val a2 = buildBlock(a1, NoFinalizedBlockHash, minerA)
    val b2 = buildBlock(a1, NoFinalizedBlockHash, minerB)
    a2.hash should not be b2.hash

    step("Make a2 the head, a1 finalized")
    ec1.engineApi.forkchoiceUpdated(a2.hash, a1.hash).explicitGet()
    head shouldBe a2.hash
    finalizedHash.value shouldBe a1.hash

    step("Switch the head to the sibling b2 with no finalized block: accepted, a1 stays finalized")
    ec1.engineApi.forkchoiceUpdated(b2.hash, NoFinalizedBlockHash).explicitGet()
    head shouldBe b2.hash
    finalizedHash.value shouldBe a1.hash

    step("Switch back to a2 with no finalized block")
    ec1.engineApi.forkchoiceUpdated(a2.hash, NoFinalizedBlockHash).explicitGet()
    head shouldBe a2.hash
    finalizedHash.value shouldBe a1.hash

    step("A finalized block that isn't on the head's chain is rejected, the finalized block doesn't change")
    ec1.engineApi.forkchoiceUpdated(a2.hash, b2.hash).isLeft shouldBe true
    finalizedHash.value shouldBe a1.hash
  }

  override protected def startNodes(): Unit = {
    ec1.start()
    ec1.logPorts()
  }

  override protected def stopNodes(): Unit = ec1.stop()

  override protected def setupChain(): Unit = {}

  override protected def step(text: String): Unit = log.debug(text) // No Waves node to print to
}
