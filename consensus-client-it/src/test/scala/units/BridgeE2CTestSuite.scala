package units

import com.wavesplatform.account.KeyPair
import com.wavesplatform.api.http.ApiError.ScriptExecutionError
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.utils.EthEncoding
import org.web3j.protocol.core.DefaultBlockParameterName

class BridgeE2CTestSuite extends TwoNodesTestSuite {
  "L2-379 Checking balances in EL->CL transfers" in {
    val elSender    = elRichAccount1
    val clRecipient = clRichAccount1
    val userAmount  = 1
    val wavesAmount = UnitsConvert.toWavesAmount(userAmount)

    log.info("Broadcast Bridge.sendNative transaction")
    def bridgeBalance       = ec1.web3j.ethGetBalance(ec1.elBridge.address.hex, DefaultBlockParameterName.LATEST).send().getBalance
    val bridgeBalanceBefore = bridgeBalance
    val sendTxnReceipt      = ec1.elBridge.sendNativeAndWait(elSender, clRecipient.toAddress, UnitsConvert.toWei(userAmount))

    val bridgeBalanceAfter = bridgeBalance
    withClue("1. The balance of Bridge contract wasn't changed: ") {
      bridgeBalanceAfter shouldBe bridgeBalanceBefore
    }

    val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
    log.info(s"Block with transaction: $blockHash")

    val logsInBlock = ec1.engineApi.getLogs(blockHash, ec1.elBridge.address, Bridge.ElSentNativeEventTopic).explicitGet()

    val transferEvents = logsInBlock.map { x =>
      Bridge.ElSentNativeEvent.decodeArgs(x.data).explicitGet()
    }
    log.info(s"Transfer events: ${transferEvents.mkString(", ")}")

    val sendTxnLogIndex = logsInBlock.indexWhere(_.transactionHash == sendTxnReceipt.getTransactionHash)
    val transferProofs  = Bridge.mkTransferProofs(transferEvents, sendTxnLogIndex).reverse

    log.info(s"Wait block $blockHash on contract")
    val blockConfirmationHeight = retry {
      waves1.chainContract.getBlock(blockHash).get.height
    }

    val currFinalizedHeight = waves1.chainContract.getFinalizedBlock.height
    if (currFinalizedHeight >= blockConfirmationHeight)
      fail(s"Can't continue the test: the block ($blockConfirmationHeight) is already finalized ($currFinalizedHeight)")

    log.info("Trying to withdraw before finalization")
    def withdraw(sender: KeyPair = clRecipient) = chainContract.withdraw(
      sender = sender,
      blockHash = BlockHash(sendTxnReceipt.getBlockHash),
      merkleProof = transferProofs,
      transferIndexInBlock = sendTxnLogIndex,
      amount = wavesAmount
    )

    withClue("2. Withdraws from non-finalized blocks are denied: ") {
      val attempt1 = waves1.api.broadcast(withdraw()).left.value
      attempt1.error shouldBe ScriptExecutionError.Id
      attempt1.message should include("is not finalized")
    }

    log.info(s"Wait block $blockHash ($blockConfirmationHeight) finalization")
    retry {
      val currFinalizedHeight = waves1.chainContract.getFinalizedBlock.height
      log.info(s"Current finalized height: $currFinalizedHeight")
      if (currFinalizedHeight < blockConfirmationHeight) fail("Not yet finalized")
    }

    def receiverBalance: Long = waves1.api.balance(clRecipient.toAddress, waves1.chainContract.token)
    val receiverBalanceBefore = receiverBalance

    log.info(
      s"Broadcast withdraw transaction: transferIndexInBlock=$sendTxnLogIndex, amount=$wavesAmount, " +
        s"merkleProof={${transferProofs.map(EthEncoding.toHexString).mkString(",")}}"
    )
    waves1.api.broadcastAndWait(withdraw())

    val balanceAfter = receiverBalance
    withClue("3. Tokens received: ") {
      balanceAfter shouldBe (receiverBalanceBefore + wavesAmount)
    }
  }
}
