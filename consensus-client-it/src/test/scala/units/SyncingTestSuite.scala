package units

import com.wavesplatform.utils.EthEncoding
import org.web3j.crypto.{RawTransaction, TransactionEncoder}
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.response.{EthSendTransaction, TransactionReceipt}
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.utils.Convert
import units.docker.EcContainer

import java.math.BigInteger

class SyncingTestSuite extends BaseDockerTestSuite {
  private val elSender = elRichAccount1
  private val amount   = Convert.toWei("1", Convert.Unit.ETHER).toBigInteger

  "L2-381 EL transactions appear after rollback" in {
    step("Send transaction 1")
    val txn1Result = sendTxn(0)
    waitForTxn(txn1Result)

    val height1 = waves1.api.height

    step("Wait for next epoch")
    waves1.api.waitForHeight(height1 + 1)

    step("Send transactions 2 and 3")
    val txn2Result = sendTxn(1)
    val txn3Result = sendTxn(2)

    val txn2Receipt = waitForTxn(txn2Result)
    val txn3Receipt = waitForTxn(txn3Result)

    val blocksWithTxns = List(txn2Receipt, txn3Receipt).map(x => x.getBlockNumber -> x.getBlockHash).toMap
    step(s"Waiting blocks ${blocksWithTxns.mkString(", ")} on contract")
    blocksWithTxns.foreach { case (_, blockHash) =>
      retry {
        chainContract.getBlock(BlockHash(blockHash)).get
      }
    }

    step("Rollback CL")
    waves1.api.rollback(height1)

    step("Wait for EL mining")
    waves1.api.waitForHeight(height1 + 2)

    step(s"Waiting blocks ${blocksWithTxns.mkString(", ")} disappear")
    blocksWithTxns.foreach { case (_, blockHash) =>
      retry {
        if (chainContract.getBlock(BlockHash(blockHash)).nonEmpty) throw new RuntimeException(s"Expected $blockHash to disappear")
      }
    }

    blocksWithTxns.foreach { case (height, blockHash) =>
      retry {
        val block = ec1.web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(height), false).send().getBlock
        block.getHash shouldNot be(blockHash)
      }
    }

    step("Waiting transactions 2 and 3 on EL")
    waitForTxn(txn2Result)
    waitForTxn(txn3Result)
  }

  private def sendTxn(nonce: Long): EthSendTransaction = {
    val rawTransaction = RawTransaction.createEtherTransaction(
      EcContainer.ChainId,
      BigInteger.valueOf(nonce),
      DefaultGasProvider.GAS_LIMIT,
      "0x0000000000000000000000000000000000000000",
      amount,
      BigInteger.ZERO,
      DefaultGasProvider.GAS_PRICE
    )
    val signedTransaction = EthEncoding.toHexString(TransactionEncoder.signMessage(rawTransaction, elSender))
    ec1.web3j.ethSendRawTransaction(signedTransaction).send()
  }

  private def waitForTxn(txnResult: EthSendTransaction): TransactionReceipt = retry {
    ec1.web3j.ethGetTransactionReceipt(txnResult.getTransactionHash).send().getTransactionReceipt.get()
  }
}
