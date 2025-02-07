package units

import com.wavesplatform.transaction.TxHelpers
import units.client.engine.model.BlockNumber
import units.eth.EthAddress

class NativeBridgeC2ETestSuite extends BaseDockerTestSuite {
  private val clSender          = clRichAccount1
  private val elReceiver        = elRichAccount1
  private val elReceiverAddress = EthAddress.unsafeFrom(elReceiver.getAddress)

  private val userAmount  = 1
  private val wavesAmount = UnitsConvert.toUnitsInWaves(userAmount)
  private val gweiAmount  = UnitsConvert.toGwei(userAmount)

  "L2-380 Checking balances in CL->EL transfers" in {
    def clAssetQuantity: Long      = waves1.api.assetQuantity(chainContract.nativeTokenId)
    def chainContractBalance: Long = waves1.api.balance(chainContractAddress, chainContract.nativeTokenId)

    val clAssetQuantityBefore      = clAssetQuantity
    val chainContractBalanceBefore = chainContractBalance

    val elCurrHeight = ec1.web3j.ethBlockNumber().send().getBlockNumber.intValueExact()

    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        sender = clSender,
        destElAddress = elReceiverAddress,
        asset = chainContract.nativeTokenId,
        amount = wavesAmount
      )
    )

    val chainContractBalanceAfter = chainContractBalance
    withClue("1. Chain contract balance unchanged: ") {
      chainContractBalanceAfter shouldBe chainContractBalanceBefore
    }

    val clAssetQuantityAfter = clAssetQuantity
    withClue("1. Tokens burned: ") {
      clAssetQuantityAfter shouldBe (clAssetQuantityBefore - wavesAmount)
    }

    val withdrawal = Iterator
      .from(elCurrHeight + 1)
      .map { h =>
        eventually {
          ec1.engineApi.getBlockByNumber(BlockNumber.Number(h)).toOption.flatten.value
        }
      }
      .flatMap(_.withdrawals)
      .find(_.address == elReceiverAddress)
      .head

    withClue("2. Expected amount: ") {
      withdrawal.amount shouldBe gweiAmount
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Prepare: issue tokens on chain contract and transfer to a user")
    waves1.api.broadcastAndWait(
      TxHelpers.reissue(
        asset = chainContract.nativeTokenId,
        sender = chainContractAccount,
        amount = wavesAmount
      )
    )
    waves1.api.broadcastAndWait(
      TxHelpers.transfer(
        from = chainContractAccount,
        to = clSender.toAddress,
        amount = wavesAmount,
        asset = chainContract.nativeTokenId
      )
    )
  }
}
