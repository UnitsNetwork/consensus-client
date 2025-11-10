package units.block.validation

import com.wavesplatform.*
import com.wavesplatform.account.*
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import org.web3j.protocol.core.DefaultBlockParameterName
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.engine.model.{EcBlock, Withdrawal}
import units.el.*
import units.eth.{EthAddress, Gwei}
import units.{BlockHash, NetworkL2Block, TestNetworkClient}

class NativeUnexpectedWithdrawalTestSuite extends BaseBlockValidationSuite {
  "Invalid block: native token, unexpected extra withdrawal" in {
    val ethBalanceBefore       = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    val elParentBlock: EcBlock = getMainChainLastBlock

    val rewardWithdrawal     = mkRewardWithdrawal(elParentBlock)
    val unexpectedWithdrawal = Withdrawal(rewardWithdrawal.index + 1, elRecipient, Gwei.ofRawGwei(3_000_000_000L))
    val withdrawals          = Vector(rewardWithdrawal, unexpectedWithdrawal)

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeETHTransaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        from = EthAddress.unsafeFrom(clSender.toAddress),
        to = elRecipient,
        amount = clNativeTokenAmount.longValue
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        chainContract.nativeTokenId,
        clNativeTokenAmount
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

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(simulatedBlockHash)
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Deposited transaction doesn't affect balances")
    val ethBalanceAfter = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    ethBalanceBefore shouldBe ethBalanceAfter

    step("Assertion: head is not moved to simulated block")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.hash shouldNot be(simulatedBlockHash)
  }

  override def beforeAll(): Unit = setupForNativeTokenTransfer()
}
