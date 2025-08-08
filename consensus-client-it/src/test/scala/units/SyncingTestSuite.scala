package units

import com.wavesplatform.state.Height
import com.wavesplatform.utils.EthEncoding
import org.web3j.crypto.{RawTransaction, TransactionEncoder}
import org.web3j.protocol.core.methods.response.{EthSendTransaction, TransactionReceipt}
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.utils.Convert
import units.docker.EcContainer

import java.math.BigInteger
import scala.jdk.OptionConverters.RichOptional

class SyncingTestSuite extends BaseDockerTestSuite {
  private val elSender = elRichAccount1
  private val amount   = Convert.toWei("1", Convert.Unit.ETHER).toBigInteger

  "L2-381 EL transactions appear after rollback" in {
    step("Send transaction 1")
    val txn1Result = sendTxn(0)
    waitForTxn(txn1Result)

    val height1 = waves1.api.height()

    step("Wait for next epoch")
    waves1.api.waitForHeight(height1 + 1)

    step("Send transactions 2 and 3")
    val txn2Result = sendTxn(1)
    val txn3Result = sendTxn(2)

    val txn2ReceiptBeforeRb = waitForTxn(txn2Result)
    val txn3ReceiptBeforeRb = waitForTxn(txn3Result)

    val blocksWithTxnsBeforeRb                   = List(txn2ReceiptBeforeRb, txn3ReceiptBeforeRb).map(x => x.getBlockNumber -> x.getBlockHash).toMap
    val (earliestBlockHeight, earliestBlockHash) = blocksWithTxnsBeforeRb.minBy(_._1)
    chainContract.waitForHeight(earliestBlockHeight.longValueExact())
    val contractBlock = chainContract.getBlock(BlockHash(earliestBlockHash)).getOrElse(fail(s"Can't find $earliestBlockHash on contract"))

    step("Rollback CL")
    val elWaitHeight = ec1.web3j.ethBlockNumber().send().getBlockNumber.intValueExact() + 1
    waves1.api.rollback(Height(contractBlock.epoch - 1))

    step("Wait for EL blocks")
    eventually {
      ec1.web3j.ethBlockNumber().send().getBlockNumber.intValueExact() >= elWaitHeight
    }

    step("Waiting transactions 2 and 3 on EL")
    val txn2ReceiptAfterRb = waitForTxn(txn2Result)
    val txn3ReceiptAfterRb = waitForTxn(txn3Result)

    withClue("Transactions moved: ") {
      txn2ReceiptAfterRb.getBlockHash should not be txn2ReceiptBeforeRb.getBlockHash
      txn3ReceiptAfterRb.getBlockHash should not be txn3ReceiptBeforeRb.getBlockHash
    }
  }

  private def sendTxn(nonce: Long): EthSendTransaction = {
    val rawTransaction = RawTransaction.createEtherTransaction(
      EcContainer.ChainId,
      BigInteger.valueOf(nonce),
      DefaultGasProvider.GAS_LIMIT,
      "0x0000000000000000000000000000000000000000",
      amount,
      BigInteger.ONE,
      DefaultGasProvider.GAS_PRICE
    )
    val signedTransaction = EthEncoding.toHexString(TransactionEncoder.signMessage(rawTransaction, elSender))
    ec1.web3j.ethSendRawTransaction(signedTransaction).send()
  }

  private def waitForTxn(txnResult: EthSendTransaction): TransactionReceipt = eventually {
    ec1.web3j.ethGetTransactionReceipt(txnResult.getTransactionHash).send().getTransactionReceipt.toScala.value
  }
}
