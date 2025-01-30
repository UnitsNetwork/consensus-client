package units

import com.wavesplatform.api.http.ApiError.ScriptExecutionError
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import units.client.engine.model.BlockNumber
import units.el.IssuedTokenBridge
import units.eth.EthAddress

class IssuedTokenBridgeC2ETestSuite extends BaseDockerTestSuite {
  private val clSender          = clRichAccount1
  private val elReceiver        = elRichAccount1
  private val elReceiverAddress = EthAddress.unsafeFrom(elReceiver.getAddress)

  private lazy val issueAssetTxn = TxHelpers.issue(clSender)
  private lazy val issueAsset    = IssuedAsset(issueAssetTxn.id())

  private val userAmount = BigDecimal("0.55")

  // TODO: Test Waves asset too
  "Checking balances in CL->EL transfers" in {
    step("1. Try to transfer an asset without registration")
    def transferTxn: InvokeScriptTransaction =
      ChainContract.transfer(clSender, elRichAddress1, issueAsset, UnitsConvert.toWavesAmount(userAmount), ChainContract.TransferIssuedFunctionName)

    val rejected = waves1.api.broadcast(transferTxn).left.value
    rejected.error shouldBe ScriptExecutionError.Id
    rejected.message should include(s"Can't find in a registry: $issueAsset")

    step("2. Enable the asset in the registry")
    waves1.api.broadcastAndWait(ChainContract.registerToken(issueAsset, elIssuedTokenBridgeAddress))

    step("3. Try to transfer the asset")
    val elCurrHeight = ec1.web3j.ethBlockNumber().send().getBlockNumber.intValueExact()
    waves1.api.broadcastAndWait(transferTxn)

    val withdrawal = Iterator
      .from(elCurrHeight + 1)
      .flatMap { h =>
        eventually {
          val block = ec1.engineApi.getBlockByNumber(BlockNumber.Number(h)).toOption.flatten.value
          ec1.engineApi.getLogs(block.hash, List(elIssuedTokenBridgeAddress), List(IssuedTokenBridge.ERC20BridgeFinalized.Topic)).value
        }
      }
      .map(event => IssuedTokenBridge.ERC20BridgeFinalized.decodeLog(event.data).explicitGet())
      .find(_.recipient == elReceiverAddress)
      .head

    withClue("Expected amount: ") {
      withdrawal.clAmount shouldBe UnitsConvert.toWavesAmount(userAmount)
    }

    // TODO check the balance
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Prepare: issue asset")
    waves1.api.broadcastAndWait(issueAssetTxn)
  }
}
