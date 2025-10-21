package units

import com.wavesplatform.*
import com.wavesplatform.account.*
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.engine.model.EcBlock
import units.el.*
import units.eth.EthAddress
import units.{BlockHash, TestNetworkClient}

class BlockValidationAssetInvalidTokenTestSuite extends BaseBlockValidationSuite {
  "Invalid block: asset token, invalid token address" in {
    val balanceBefore          = terc20.getBalance(elRecipient)
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val invalidTokenAddress = WWavesAddress

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeErc20Transaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        token = invalidTokenAddress,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
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

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
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
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Deposited transaction doesn't change balance")
    val balanceAfter = terc20.getBalance(elRecipient)
    balanceAfter.longValue shouldBe balanceBefore.longValue

    step("Assertion: EL height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForAssetTokenTransfer()
}
