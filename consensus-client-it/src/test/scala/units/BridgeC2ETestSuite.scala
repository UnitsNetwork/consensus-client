package units

import com.wavesplatform.transaction.TxHelpers
import units.eth.EthAddress

class BridgeC2ETestSuite extends OneNodeTestSuite {
  protected val clSender    = clRichAccount1
  protected val elReceiver  = elRichAccount1
  protected val userAmount  = 1
  protected val wavesAmount = UnitsConvert.toWavesAmount(userAmount)

  "L2-380 Checking balances in CL->EL transfers" in {
    def clAssetQuantity: Long      = waves1.api.assetQuantity(waves1.chainContract.token)
    def chainContractBalance: Long = waves1.api.balance(chainContractAddress, waves1.chainContract.token)

    val clAssetQuantityBefore      = clAssetQuantity
    val chainContractBalanceBefore = chainContractBalance

    waves1.api.broadcastAndWait(
      chainContract.transfer(
        sender = clSender,
        destElAddress = EthAddress.unsafeFrom(elReceiver.getAddress),
        asset = waves1.chainContract.token,
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
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    log.info("Prepare: issue tokens on chain contract and transfer to a user")
    waves1.api.broadcastAndWait(
      TxHelpers.reissue(
        asset = waves1.chainContract.token,
        sender = chainContractAccount,
        amount = wavesAmount
      )
    )
    waves1.api.broadcastAndWait(
      TxHelpers.transfer(
        from = chainContractAccount,
        to = clSender.toAddress,
        amount = wavesAmount,
        asset = waves1.chainContract.token
      )
    )
  }
}
