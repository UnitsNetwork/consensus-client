package units.block.validation

import com.wavesplatform.account.*
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.engine.model.EcBlock
import units.el.*
import units.*

class AssetInvalidSenderTestSuite extends BaseBlockValidationSuite {
  "Invalid block: asset token, invalid sender address" in {
    val balanceBefore          = terc20.getBalance(elRecipient)
    val elParentBlock: EcBlock = getMainChainLastBlock

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val invalidSenderAddress = additionalMiner1RewardAddress

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeErc20Transaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        token = TErc20Address,
        from = invalidSenderAddress,
        to = elRecipient,
        amount = EAmount(elAssetTokenAmount.bigInteger)
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        issueAsset,
        clAssetTokenAmount
      )
    )

    waves1.api.waitForHeight(getBlockEpoch(elParentBlock.hash).get + 1)

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

    step("Assertion: Deposited transaction doesn't change balance")
    val balanceAfter = terc20.getBalance(elRecipient)
    balanceAfter.longValue shouldBe balanceBefore.longValue

    step("Assertion: head is not moved to simulated block")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.hash shouldNot be(simulatedBlockHash)
  }

  override def beforeAll(): Unit = setupForAssetTokenTransfer()
}
