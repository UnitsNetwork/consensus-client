package units

import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.state.IntegerDataEntry
import org.web3j.crypto.Credentials
import org.web3j.protocol.core.DefaultBlockParameterName
import units.eth.EthAddress

class C2ENativeTokenTransfersViaDepositsTestSuite extends BaseDockerTestSuite {
  val elPoorAccount1PrivateKey = "0x1c740114034be2f658637dc08f9c3689872c57b59cc08a98d9a440392b4e69ee"
  val elPoorAccount1           = Credentials.create(elPoorAccount1PrivateKey)
  val elPoorAddress1           = EthAddress.unsafeFrom(elPoorAccount1.getAddress) // 0xc8b418b90e063cc2962ee24dfff6ce72f13ac39e

  private val clSender          = clRichAccount1
  private val elReceiver        = elPoorAccount1
  private val elReceiverAddress = EthAddress.unsafeFrom(elReceiver.getAddress)

  private val userAmount = 1
  private val clAmount   = UnitsConvert.toUnitsInWaves(userAmount)
  private val elAmount   = UnitsConvert.toWei(userAmount)

  "C2E native token transfers via deposited transactions" in {
    def getElBalance: BigInt = ec1.web3j.ethGetBalance(elReceiverAddress.hex, DefaultBlockParameterName.PENDING).send().getBalance
    val elBalanceBefore      = getElBalance
    elBalanceBefore shouldBe 0

    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        sender = clSender,
        destElAddress = elReceiverAddress,
        asset = chainContract.nativeTokenId,
        amount = clAmount
      )
    )

    withClue("Expected amount: ") {
      val expectedBalanceAfter = elBalanceBefore + elAmount
      eventually {
        val elBalanceAfter = getElBalance
        UnitsConvert.toUser(elBalanceAfter, NativeTokenElDecimals) shouldBe UnitsConvert.toUser(expectedBalanceAfter, NativeTokenElDecimals)
      }
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Set native token transfers via deposits feature activation epoch")
    val featureActivationHeight = waves1.api.height() + 2
    val txRes = waves1.api.broadcastAndWait(
      TxHelpers.dataEntry(
        chainContractAccount,
        IntegerDataEntry("nativeTokenDepositTransfersActivationEpoch", featureActivationHeight)
      )
    )

    step("Wait for feature activation")
    waves1.api.waitForHeight(featureActivationHeight)

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
