package units

import org.web3j.protocol.core.methods.response.{EthSendTransaction, TransactionReceipt}
import units.el.EvmEncoding

import scala.jdk.OptionConverters.*
import scala.util.chaining.*

trait Web3JHelpers { this: BaseDockerTestSuite =>
  protected def waitFor(txn: EthSendTransaction): TransactionReceipt = eventually {
    ec1.web3j.ethGetTransactionReceipt(txn.getTransactionHash).send().getTransactionReceipt.toScala.value.tap { r =>
      if (!r.isStatusOK) {
        fail(s"Expected successful send_approve, got: tx=${EvmEncoding.decodeRevertReason(r.getRevertReason)}")
      }
    }
  }
}
