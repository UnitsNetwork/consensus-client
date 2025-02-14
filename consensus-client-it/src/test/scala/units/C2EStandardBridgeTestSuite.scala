package units

import com.wavesplatform.api.http.ApiError.ScriptExecutionError
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import units.eth.EthAddress

class C2EStandardBridgeTestSuite extends BaseDockerTestSuite {
  private val clSender          = clRichAccount1
  private val elReceiver        = elRichAccount1
  private val elReceiverAddress = EthAddress.unsafeFrom(elReceiver.getAddress)

  private val issueAssetTxn   = TxHelpers.issue(clSender, decimals = 8)
  private val issueAsset      = IssuedAsset(issueAssetTxn.id())
  private val elAssetDecimals = 18

  private val userAmount = BigDecimal("0.55")
  private val elAmount   = UnitsConvert.toAtomic(userAmount, elAssetDecimals)

  // TODO: Test Waves asset too
  "Checking balances in CL->EL transfers" in {
    step("1. Try to transfer an asset without registration")
    def transferTxn: InvokeScriptTransaction =
      ChainContract.transfer(
        clSender,
        elReceiverAddress,
        issueAsset,
        UnitsConvert.toWavesAtomic(userAmount, issueAssetTxn.decimals.value),
        ChainContract.AssetTransferFunctionName
      )

    val rejected = waves1.api.broadcast(transferTxn).left.value
    rejected.error shouldBe ScriptExecutionError.Id
    rejected.message should include(s"Can't find in a registry: $issueAsset")
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Prepare: issue asset")
    waves1.api.broadcastAndWait(issueAssetTxn)
  }
}
