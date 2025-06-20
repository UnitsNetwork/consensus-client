package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.utils.EthEncoding
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.utils.Convert
import units.el.{BridgeMerkleTree, EvmEncoding, NativeBridgeClient}

import scala.jdk.OptionConverters.RichOptional

class E2CNativeBridgeTestSuite extends BaseDockerTestSuite {
  private val elSender    = elRichAccount1
  private val clRecipient = clRichAccount1

  private val userAmount  = 1
  private val wavesAmount = UnitsConvert.toUnitsInWaves(userAmount)

  private val tenGwei = BigInt(Convert.toWei("10", Convert.Unit.GWEI).toBigIntegerExact)

  "Negative" - {
    def test(amount: BigInt, expectedError: String): Unit = {
      val e = nativeBridge.callRevertedSendNative(elSender, clRecipient.toAddress, amount)
      e should include(expectedError)
    }

    "L2-264 Amount should % 10 Gwei" in test(tenGwei + 1, "Sent value 10000000001 must be a multiple of 10000000000")

    "L2-265 Amount should be between 10 and MAX_AMOUNT_IN_WEI Gwei" in {
      withClue("1. Less than 10 Gwei: ") {
        test(1, "Sent value 1 must be greater or equal to 10000000000")
      }

      withClue("2. More than MAX_AMOUNT_IN_WEI: ") {
        val maxAmountInWei = BigInt(Long.MaxValue) * tenGwei
        val biggerAmount   = (maxAmountInWei / tenGwei + 1) * tenGwei
        test(biggerAmount, s"Sent value $biggerAmount must be less or equal to $maxAmountInWei")
      }
    }
  }

  "Positive" - {
    def sendNative(amount: BigInt = UnitsConvert.toWei(userAmount)): TransactionReceipt = {
      val txnResult = nativeBridge.sendSendNative(elSender, clRecipient.toAddress, amount)

      // To overcome a failed block confirmation in a new epoch issue
      chainContract.waitForHeight(ec1.web3j.ethBlockNumber().send().getBlockNumber.longValueExact() + 2)

      eventually {
        val r = ec1.web3j.ethGetTransactionReceipt(txnResult.getTransactionHash).send().getTransactionReceipt.toScala.value
        if (!r.isStatusOK) fail(s"Expected successful sendNative, got: ${EvmEncoding.decodeRevertReason(r.getRevertReason)}")
        r
      }
    }

    "L2-325 Sent tokens burned" in {
      def burnedTokens       = ec1.web3j.ethGetBalance(NativeBridgeClient.BurnAddress.hex, DefaultBlockParameterName.LATEST).send().getBalance
      val burnedTokensBefore = BigInt(burnedTokens)

      val transferAmount = tenGwei
      sendNative(transferAmount)
      val burnedTokensAfter = BigInt(burnedTokens)

      burnedTokensAfter shouldBe (transferAmount + burnedTokensBefore)
    }

    "L2-379 Checking balances in EL->CL transfers" in {
      step("Broadcast Bridge.sendNative transaction")
      def bridgeBalance       = ec1.web3j.ethGetBalance(NativeBridgeAddress.hex, DefaultBlockParameterName.LATEST).send().getBalance
      val bridgeBalanceBefore = bridgeBalance
      val sendTxnReceipt      = sendNative()

      withClue("1. The balance of Bridge contract wasn't changed: ") {
        val bridgeBalanceAfter = bridgeBalance
        bridgeBalanceAfter shouldBe bridgeBalanceBefore
      }

      val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
      step(s"Block with transaction: $blockHash")

      val logsInBlock     = ec1.engineApi.getLogs(blockHash, List(NativeBridgeAddress, StandardBridgeAddress)).explicitGet()
      val sendTxnLogIndex = logsInBlock.indexWhere(_.transactionHash == sendTxnReceipt.getTransactionHash)
      sendTxnLogIndex shouldBe >=(0)

      val transferProofs = BridgeMerkleTree.mkTransferProofs(logsInBlock, sendTxnLogIndex).explicitGet().reverse

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
          s"Broadcast withdraw transaction: transferIndexInBlock=$sendTxnLogIndex, amount=$wavesAmount, " +
            s"merkleProof={${transferProofs.map(EthEncoding.toHexString).mkString(",")}}"
        )

        def receiverBalance: Long = waves1.api.balance(clRecipient.toAddress, chainContract.nativeTokenId)
        val receiverBalanceBefore = receiverBalance

        waves1.api.broadcastAndWait(
          ChainContract.withdraw(
            sender = clRecipient,
            blockHash = BlockHash(sendTxnReceipt.getBlockHash),
            merkleProof = transferProofs,
            transferIndexInBlock = sendTxnLogIndex,
            amount = wavesAmount
          )
        )

        val balanceAfter = receiverBalance
        balanceAfter shouldBe (receiverBalanceBefore + wavesAmount)
      }
    }
  }
}
