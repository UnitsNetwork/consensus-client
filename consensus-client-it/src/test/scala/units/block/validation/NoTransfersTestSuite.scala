package units.block.validation

import com.wavesplatform.account.*
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.engine.model.EcBlock
import units.{BlockHash, NetworkL2Block, TestNetworkClient}

class NoTransfersTestSuite extends BaseBlockValidationSuite {
  "Valid block: no transfers" in {
    val elParentBlock: EcBlock = getMainChainLastBlock

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val depositedTransactions = Vector.empty

    waves1.api.waitForHeight(getBlockEpoch(elParentBlock.hash).get + 1)
    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)
    // Note: No transfers on the chain contract in this test case

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.hexNoPrefix).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.hexNoPrefix).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(simulatedBlockHash)
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: EL height grows")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.hash shouldBe simulatedBlockHash
  }

  override def beforeAll(): Unit = setupForNativeTokenTransfer()
}
