package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.utils.EthEncoding
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.utils.Convert
import units.el.{EvmEncoding, StandardBridge}
import units.eth.EthAddress

import scala.jdk.OptionConverters.RichOptional

// TODO: asset registered in EL/CL first cases, WAVES
class StandardBridgeE2CTestSuite extends BaseDockerTestSuite {
  private val clTokenOwner = clRichAccount2
  private val clRecipient  = clRichAccount1
  private val elSender     = elRichAccount1

  private val issueAssetTxn   = TxHelpers.issue(clTokenOwner, decimals = 8)
  private val issueAsset      = IssuedAsset(issueAssetTxn.id())
  private val elAssetDecimals = 18

  private val userAmount = 1
  private val clAmount   = UnitsConvert.toWavesAtomic(userAmount, issueAssetTxn.decimals.value)
  private val elAmount   = UnitsConvert.toAtomic(userAmount, elAssetDecimals)

  private val testTransfers  = 2
  private val enoughClAmount = clAmount * testTransfers
  private val enoughElAmount = elAmount * testTransfers

  private val tenGwei = BigInt(Convert.toWei("10", Convert.Unit.GWEI).toBigIntegerExact)

  // TODO: Because we have to get a token from dictionary before amount checks. Fix with unit tests for contract
  "Negative" ignore {
    def test(amount: BigInt, expectedError: String): Unit = {
      val e = elStandardBridge.getRevertReasonForBridge(elSender, clRecipient.toAddress, amount)
      e should include(expectedError)
    }

    "Amount should % 10 Gwei" in test(tenGwei + 1, "Sent value 10000000001 must be a multiple of 10000000000")

    "Amount should be between 10 and MAX_AMOUNT_IN_WEI Gwei" in {
      withClue("1. Less than 10 Gwei: ") {
        test(1, "Sent value 1 must be greater or equal to 10000000000")
      }

      withClue("2. More than MAX_AMOUNT_IN_WEI: ") {
        val maxAmountInWei = BigInt(Long.MaxValue) * tenGwei
        val biggerAmount   = (maxAmountInWei / tenGwei + 1) * tenGwei
        test(biggerAmount, s"Sent value $biggerAmount must be less or equal to $maxAmountInWei")
      }
    }

    "Can't transfer without registration" in test(elAmount, "Token is not registered")
  }

  "Positive" - {
    def sendBridge(ethAmount: BigInt): TransactionReceipt = {
      val txnResult = elStandardBridge.sendBridge(elSender, clRecipient.toAddress, ethAmount)

      // To overcome a failed block confirmation in a new epoch issue
      chainContract.waitForHeight(ec1.web3j.ethBlockNumber().send().getBlockNumber.longValueExact() + 2)

      eventually {
        val r = ec1.web3j.ethGetTransactionReceipt(txnResult.getTransactionHash).send().getTransactionReceipt.toScala.value
        if (!r.isStatusOK) fail(s"Expected successful sendBridge, got: tx=${EvmEncoding.decodeRevertReason(r.getRevertReason)}")
        r
      }
    }

    "Checking balances in EL->CL transfers" in {
      step("Register token")
      waves1.api.broadcastAndWait(ChainContract.registerToken(issueAsset, elStandardBridgeAddress, 18))
      eventually {
        elStandardBridge.isRegistered(elStandardBridgeAddress) shouldBe true
      }

      step("Broadcast IssuedTokenBridge.sendBridge transaction")
      val sendTxnReceipt = sendBridge(elAmount)

      val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
      step(s"Block with transaction: $blockHash")

      val logsInBlock =
        ec1.engineApi.getLogs(blockHash, List(elStandardBridgeAddress), List(StandardBridge.ERC20BridgeInitiated.Topic)).explicitGet()

      val transferEvents = logsInBlock.map { x =>
        StandardBridge.ERC20BridgeInitiated.decodeLog(x.data).explicitGet()
      }
      step(s"Transfer events: ${transferEvents.mkString(", ")}")

      val sendTxnLogIndex = logsInBlock.indexWhere(_.transactionHash == sendTxnReceipt.getTransactionHash)
      sendTxnLogIndex shouldBe >=(0)

      val transferProofs = StandardBridge.ERC20BridgeInitiated.mkTransferProofs(transferEvents, sendTxnLogIndex).reverse

      step(s"Wait block $blockHash on contract")
      val blockConfirmationHeight = eventually {
        chainContract.getBlock(blockHash).value.height
      }

      step(s"Wait block $blockHash ($blockConfirmationHeight) finalization")
      eventually {
        val currFinalizedHeight = chainContract.getFinalizedBlock.height
        step(s"Current finalized height: $currFinalizedHeight")
        currFinalizedHeight should be >= blockConfirmationHeight
      }

      withClue("3. Tokens received: ") {
        step(
          s"Broadcast withdrawIssued transaction: transferIndexInBlock=$sendTxnLogIndex, amount=$clAmount, " +
            s"merkleProof={${transferProofs.map(EthEncoding.toHexString).mkString(",")}}"
        )

        def receiverBalance: Long = waves1.api.balance(clRecipient.toAddress, issueAsset)
        val receiverBalanceBefore = receiverBalance

        waves1.api.broadcastAndWait(
          ChainContract.withdrawIssued(
            sender = clRecipient,
            blockHash = blockHash,
            merkleProof = transferProofs,
            transferIndexInBlock = sendTxnLogIndex,
            amount = clAmount,
            asset = issueAsset
          )
        )

        val balanceAfter = receiverBalance
        balanceAfter shouldBe (receiverBalanceBefore + clAmount)
      }
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Prepare: mint EL token")
    elStandardBridge.sendMint(elSender, EthAddress.unsafeFrom(elSender.getAddress), enoughElAmount)

    step("Prepare: issue CL asset")
    waves1.api.broadcastAndWait(issueAssetTxn)

    step("Prepare: move assets and enable the asset in the registry")
    waves1.api.broadcast(TxHelpers.transfer(clTokenOwner, chainContractAddress, enoughClAmount, issueAsset))
  }
}
