package units

import com.wavesplatform.api.http.ApiError.ScriptExecutionError
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import units.eth.EthAddress

class AssetBridgeC2ETestSuite extends BaseDockerTestSuite {
  private val clSender          = clRichAccount1
  private val elReceiver        = elRichAccount1
  private val elReceiverAddress = EthAddress.unsafeFrom(elReceiver.getAddress)

  private lazy val issueAssetTxn = TxHelpers.issue(clSender, decimals = 8)
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
    waves1.api.broadcastAndWait(ChainContract.registerToken(issueAsset, elIssuedTokenBridgeAddress, 18))
    eventually {
      elIssuedTokenBridge.tokensRatio(elIssuedTokenBridgeAddress) shouldBe defined
    }

    step("3. Try to transfer the asset")
    val balanceBefore = elIssuedTokenBridge.getBalance(elReceiverAddress)
    waves1.api.broadcastAndWait(transferTxn)

    val expectedBalanceAfter = balanceBefore + UnitsConvert.toWei(userAmount)
    eventually {
      val balanceAfter = elIssuedTokenBridge.getBalance(elReceiverAddress)
      balanceAfter shouldBe expectedBalanceAfter
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Prepare: issue asset")
    waves1.api.broadcastAndWait(issueAssetTxn)
  }
}
