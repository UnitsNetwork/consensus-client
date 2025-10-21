package units

import com.wavesplatform.state.IntegerDataEntry
import com.wavesplatform.transaction.TxHelpers
import org.web3j.protocol.core.DefaultBlockParameterName
import units.eth.EthAddress

class C2ENativeTokenTransfersViaDepositsTestSuite extends BaseDockerTestSuite {
  private val clSender          = clRichAccount1
  private val elReceiver        = elRichAccount1
  private val elReceiverAddress = EthAddress.unsafeFrom(elReceiver.getAddress)

  private val userAmount = 1
  private val clAmount   = UnitsConvert.toUnitsInWaves(userAmount)
  private val elAmount   = UnitsConvert.toWei(userAmount)

  "C2E native token transfers via deposited transactions" in {
    def getElBalance: BigInt = ec1.web3j.ethGetBalance(elReceiverAddress.hex, DefaultBlockParameterName.PENDING).send().getBalance
    val elBalanceBefore      = getElBalance
    step(s"elBalanceBefore: $elBalanceBefore")

    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        sender = clSender,
        destElAddress = elReceiverAddress,
        asset = chainContract.nativeTokenId,
        amount = clAmount
      )
    )

    withClue("Expected amount: ") {
      eventually {
        val elBalanceAfter = getElBalance
        step(s"elBalanceAfter: $elBalanceAfter")
        val expectedBalanceAfter = elBalanceBefore + elAmount
        UnitsConvert.toUser(elBalanceAfter, NativeTokenElDecimals) shouldBe UnitsConvert.toUser(expectedBalanceAfter, NativeTokenElDecimals)
      }
    }
  }

  override def beforeAll(): Unit = {

    super.beforeAll()
    deploySolidityContracts()

    step("Enable token transfers")
    val activationEpoch = waves1.api.height() + 1
    waves1.api.broadcastAndWait(
      ChainContract.enableTokenTransfersWithWaves(
        StandardBridgeAddress,
        WWavesAddress,
        activationEpoch = activationEpoch
      )
    )

    step("Set strict C2E transfers feature activation epoch")
    waves1.api.broadcastAndWait(
      TxHelpers.dataEntry(
        chainContractAccount,
        IntegerDataEntry("strictC2ETransfersActivationEpoch", activationEpoch)
      )
    )

    step("Wait for features activation")
    waves1.api.waitForHeight(activationEpoch)

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
