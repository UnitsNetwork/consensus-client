package units

import com.wavesplatform.transaction.TxHelpers
import org.web3j.protocol.core.DefaultBlockParameterName
import units.eth.EthAddress

class C2ENativeBridgeTestSuite extends BaseDockerTestSuite {
  private val clSender          = clRichAccount1
  private val elReceiver        = elRichAccount1
  private val elReceiverAddress = EthAddress.unsafeFrom(elReceiver.getAddress)

  private val userAmount = 1
  private val clAmount   = UnitsConvert.toUnitsInWaves(userAmount)
  private val elAmount   = UnitsConvert.toWei(userAmount)

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
        amount = clAmount
      )
    )

    val chainContractBalanceAfter = chainContractBalance
    withClue("1. Chain contract balance unchanged: ") {
      chainContractBalanceAfter shouldBe chainContractBalanceBefore
    }

    val clAssetQuantityAfter = clAssetQuantity
    withClue("2. Tokens burned: ") {
      clAssetQuantityAfter shouldBe (clAssetQuantityBefore - clAmount)
    }

    def getBalance: BigInt = ec1.web3j.ethGetBalance(elReceiverAddress.hex, DefaultBlockParameterName.PENDING).send().getBalance
    val balanceBefore      = getBalance

    withClue("3. Expected amount: ") {
      val expectedBalanceAfter = balanceBefore + elAmount
      eventually {
        val balanceAfter = getBalance
        UnitsConvert.toUser(balanceAfter, UnitsConvert.NativeTokenElDecimals) shouldBe
          UnitsConvert.toUser(expectedBalanceAfter, UnitsConvert.NativeTokenElDecimals)
      }
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Prepare: issue tokens on chain contract and transfer to a user")
    waves1.api.broadcastAndWait(
      TxHelpers.reissue(
        asset = chainContract.nativeTokenId,
        sender = chainContractAccount,
        amount = clAmount
      )
    )
    waves1.api.broadcastAndWait(
      TxHelpers.transfer(
        from = chainContractAccount,
        to = clSender.toAddress,
        amount = clAmount,
        asset = chainContract.nativeTokenId
      )
    )
  }
}
