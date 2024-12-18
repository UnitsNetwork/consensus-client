package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxHelpers
import units.client.engine.model.BlockNumber
import units.el.IssuedTokenBridge
import units.eth.EthAddress

class IssuedTokenBridgeC2ETestSuite extends BaseDockerTestSuite {
  private val clSender          = clRichAccount1
  private val elReceiver        = elRichAccount1
  private val elReceiverAddress = EthAddress.unsafeFrom(elReceiver.getAddress)

  private lazy val issueAssetTxn = TxHelpers.issue(clSender)
  private lazy val issueAsset    = IssuedAsset(issueAssetTxn.id())

  private val issuedAssetBridge = EthAddress.unsafeFrom("0x00000000000000000000000000000155c3d06a7e")

  private val userAmount = BigDecimal("0.55")

  "Checking balances in CL->EL transfers" in {
    // step("Try to transfer an asset without registration")
    // step("Enable the asset in the registry")

    val elCurrHeight = ec1.web3j.ethBlockNumber().send().getBlockNumber.intValueExact()

    step("Try to transfer the asset")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(clSender, elRichAddress1, issueAsset, UnitsConvert.toWavesAmount(userAmount), ChainContract.TransferIssuedFunctionName)
    )

    val withdrawal = Iterator
      .from(elCurrHeight + 1)
      .flatMap { h =>
        eventually {
          val block = ec1.engineApi.getBlockByNumber(BlockNumber.Number(h)).toOption.flatten.value
          ec1.engineApi.getLogs(block.hash, issuedAssetBridge, IssuedTokenBridge.ElReceivedIssuedEvent.Topic).value
        }
      }
      .map(event => IssuedTokenBridge.ElReceivedIssuedEvent.decodeLog(event.data).explicitGet())
      .find(_.recipient == elReceiverAddress)
      .head

    withClue("2. Expected amount: ") {
      withdrawal.amount shouldBe UnitsConvert.toWei(userAmount)
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Prepare: issue asset")
    waves1.api.broadcastAndWait(issueAssetTxn)
  }
}
