package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.utils.EthEncoding
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.utils.Convert
import units.el.ElBridgeClient

class BridgeE2CTestSuite extends BaseDockerTestSuite {
  private val elSender    = elRichAccount1
  private val clRecipient = clRichAccount1
  private val userAmount  = 1
  private val wavesAmount = UnitsConvert.toWavesAmount(userAmount)

  private def sendNative(amount: BigInt = UnitsConvert.toWei(userAmount)): TransactionReceipt =
    ec1.elBridge.sendNative(elSender, clRecipient.toAddress, amount)

  private val tenGwei = BigInt(Convert.toWei("10", Convert.Unit.GWEI).toBigIntegerExact)

  "Negative" - {
    def sendNativeInvalid(amount: BigInt): TransactionException =
      try {
        sendNative(amount)
        fail(s"Expected sendNative($amount) to fail")
      } catch {
        case e: TransactionException => e
      }

    "L2-264 Amount should % 10 Gwei" in {
      val e                   = sendNativeInvalid(tenGwei + 1)
      val encodedRevertReason = e.getTransactionReceipt.get().getRevertReason
      val revertReason        = ElBridgeClient.decodeRevertReason(encodedRevertReason)
      revertReason shouldBe "Sent value 10000000001 must be a multiple of 10000000000"
    }

    "L2-265 Amount should be between 10 and MAX_AMOUNT_IN_WEI Gwei" in {
      withClue("1. Less than 10 Gwei: ") {
        val e                   = sendNativeInvalid(1)
        val encodedRevertReason = e.getTransactionReceipt.get().getRevertReason
        val revertReason        = ElBridgeClient.decodeRevertReason(encodedRevertReason)
        revertReason shouldBe "Sent value 1 must be greater or equal to 10000000000"
      }

      withClue("2. More than MAX_AMOUNT_IN_WEI: ") {
        val maxAmountInWei      = BigInt(Long.MaxValue) * tenGwei
        val biggerAmount        = (maxAmountInWei / tenGwei + 1) * tenGwei
        val e                   = sendNativeInvalid(biggerAmount)
        val encodedRevertReason = e.getTransactionReceipt.get().getRevertReason
        val revertReason        = ElBridgeClient.decodeRevertReason(encodedRevertReason)
        revertReason shouldBe s"Sent value $biggerAmount must be less or equal to $maxAmountInWei"
      }
    }
  }

  "L2-325 Sent tokens burned" in {
    def burnedTokens       = ec1.web3j.ethGetBalance(ElBridgeClient.BurnAddress.hex, DefaultBlockParameterName.LATEST).send().getBalance
    val burnedTokensBefore = BigInt(burnedTokens)

    val transferAmount = tenGwei
    sendNative(transferAmount)
    val burnedTokensAfter = BigInt(burnedTokens)

    burnedTokensAfter shouldBe (transferAmount + burnedTokensBefore)
  }

  "L2-379 Checking balances in EL->CL transfers" in {
    step("Broadcast Bridge.sendNative transaction")
    def bridgeBalance       = ec1.web3j.ethGetBalance(ec1.elBridge.address.hex, DefaultBlockParameterName.LATEST).send().getBalance
    val bridgeBalanceBefore = bridgeBalance
    val sendTxnReceipt      = sendNative()

    withClue("1. The balance of Bridge contract wasn't changed: ") {
      val bridgeBalanceAfter = bridgeBalance
      bridgeBalanceAfter shouldBe bridgeBalanceBefore
    }

    val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
    step(s"Block with transaction: $blockHash")

    val logsInBlock = ec1.engineApi.getLogs(blockHash, ec1.elBridge.address, Bridge.ElSentNativeEventTopic).explicitGet()

    val transferEvents = logsInBlock.map { x =>
      Bridge.ElSentNativeEvent.decodeArgs(x.data).explicitGet()
    }
    step(s"Transfer events: ${transferEvents.mkString(", ")}")

    val sendTxnLogIndex = logsInBlock.indexWhere(_.transactionHash == sendTxnReceipt.getTransactionHash)
    val transferProofs  = Bridge.mkTransferProofs(transferEvents, sendTxnLogIndex).reverse

    step(s"Wait block $blockHash on contract")
    val blockConfirmationHeight = retry {
      waves1.chainContract.getBlock(blockHash).get.height
    }

    step(s"Wait block $blockHash ($blockConfirmationHeight) finalization")
    retry {
      val currFinalizedHeight = waves1.chainContract.getFinalizedBlock.height
      step(s"Current finalized height: $currFinalizedHeight")
      if (currFinalizedHeight < blockConfirmationHeight) fail("Not yet finalized")
    }

    withClue("3. Tokens received: ") {
      step(
        s"Broadcast withdraw transaction: transferIndexInBlock=$sendTxnLogIndex, amount=$wavesAmount, " +
          s"merkleProof={${transferProofs.map(EthEncoding.toHexString).mkString(",")}}"
      )

      def receiverBalance: Long = waves1.api.balance(clRecipient.toAddress, waves1.chainContract.token)
      val receiverBalanceBefore = receiverBalance

      waves1.api.broadcastAndWait(
        chainContract.withdraw(
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
