package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.settings.Constants
import com.wavesplatform.utils.EthEncoding
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.utils.Convert
import units.client.engine.model.GetLogsResponseEntry
import units.eth.EthAddress

import scala.jdk.CollectionConverters.CollectionHasAsScala

class BridgeTestSuite extends TwoNodesTestSuite {
  "L2-379 Checking balances in EL->CL transfers" in {
    val elSender    = elRichAccount1
    val clRecipient = clRichAccount1
    val userAmount  = 1

    log.info("Broadcast Bridge.sendNative transaction")
    val ethAmount      = Convert.toWei(userAmount.toString, Convert.Unit.ETHER).toBigIntegerExact
    val sendTxnReceipt = ec1.elBridge.sendNativeAndWait(elSender, clRecipient.toAddress, ethAmount)

    val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
    log.info(s"Block with transaction: $blockHash")

    log.info(s"Wait block $blockHash on contract")
    val blockConfirmationHeight = retry {
      waves1.chainContract.getBlock(blockHash).get.height
    }

    log.info(s"Wait block $blockHash finalization")
    retry {
      blockConfirmationHeight <= waves1.chainContract.getFinalizedBlock.height
    }

    val rawLogsInBlock = ec1.web3j
      .ethGetLogs(new EthFilter(blockHash, ec1.elBridge.address.hex).addSingleTopic(Bridge.ElSentNativeEventTopic))
      .send()
      .getLogs
      .asScala
      .map(_.get().asInstanceOf[EthLog.LogObject])
      .toList

    val logsInBlock = rawLogsInBlock.map { x =>
      GetLogsResponseEntry(
        address = EthAddress.unsafeFrom(x.getAddress),
        data = x.getData,
        topics = x.getTopics.asScala.toList
      )
    }

    val transferEvents = logsInBlock.map { x =>
      Bridge.ElSentNativeEvent.decodeArgs(x.data).explicitGet()
    }
    log.info(s"Transfer events: ${transferEvents.mkString(", ")}")

    val sendTxnLogIndex = rawLogsInBlock.indexWhere(_.getTransactionHash == sendTxnReceipt.getTransactionHash)
    val transferProofs  = Bridge.mkTransferProofs(transferEvents, sendTxnLogIndex).reverse
    val wavesAmount     = userAmount * Constants.UnitsInWave

    def balance: Long = waves1.api.balance(clRecipient.toAddress, waves1.chainContract.token)
    val balanceBefore = balance

    log.info(
      s"Broadcast withdraw transaction: transferIndexInBlock=$sendTxnLogIndex, amount=$wavesAmount, " +
        s"merkleProof={${transferProofs.map(EthEncoding.toHexString).mkString(",")}}"
    )
    waves1.api.broadcastAndWait(
      chainContract.withdraw(
        sender = clRecipient,
        blockHash = BlockHash(sendTxnReceipt.getBlockHash),
        merkleProof = transferProofs,
        transferIndexInBlock = sendTxnLogIndex,
        amount = wavesAmount
      )
    )

    val balanceAfter = balance
    withClue("Received") {
      balanceAfter shouldBe (balanceBefore + wavesAmount)
    }
  }

  "L2-380 Checking balances in CL->EL transfers" in {}
}
