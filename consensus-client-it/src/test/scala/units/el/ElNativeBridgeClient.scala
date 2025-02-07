package units.el

import com.wavesplatform.account.Address
import com.wavesplatform.utils.ScorexLogging
import org.web3j.crypto.{Credentials, RawTransaction}
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import units.bridge.BridgeContract
import units.docker.EcContainer
import units.eth.{EthAddress, EthereumConstants}

class ElNativeBridgeClient(web3j: Web3j, address: EthAddress, gasProvider: DefaultGasProvider = new DefaultGasProvider) extends ScorexLogging {
  def sendSendNative(
      sender: Credentials,
      recipient: Address,
      amountInEther: BigInt
  ): EthSendTransaction = {
    val senderAddress = sender.getAddress
    val txnManager    = new RawTransactionManager(web3j, sender, EcContainer.ChainId)
    val funcCall      = getSendNativeFunctionCall(sender, recipient, amountInEther)

    val nonce = web3j.ethGetTransactionCount(senderAddress, DefaultBlockParameterName.PENDING).send().getTransactionCount
    val rawTxn = RawTransaction.createTransaction(
      nonce,
      gasProvider.getGasPrice,
      gasProvider.getGasLimit,
      address.hex,
      amountInEther.bigInteger,
      funcCall
    )

    log.debug(s"Send sendNative($senderAddress->$recipient: $amountInEther Wei), nonce: $nonce")
    val r = txnManager.signAndSend(rawTxn)
    if (r.hasError) throw new TransactionException(s"Can't call sendNative: ${r.getError}, ${r.getError.getMessage}")
    r
  }

  def callRevertedSendNative(
      sender: Credentials,
      recipient: Address,
      amountInEther: BigInt
  ): String = {
    val senderAddress = sender.getAddress
    val funcCall      = getSendNativeFunctionCall(sender, recipient, amountInEther)
    val txn           = Transaction.createEthCallTransaction(senderAddress, address.hex, funcCall, amountInEther.bigInteger)

    log.debug(s"Call sendNative($senderAddress->$recipient: $amountInEther Wei)")
    val r = web3j.ethCall(txn, DefaultBlockParameterName.PENDING).send()
    if (r.isReverted) r.getRevertReason
    else throw new TransactionException(s"Expected $txn to be reverted")
  }

  def getSendNativeFunctionCall(
      sender: Credentials,
      recipient: Address,
      amountInEther: BigInt
  ): String = {
    val txnManager     = new RawTransactionManager(web3j, sender, EcContainer.ChainId)
    val bridgeContract = BridgeContract.load(address.hex, web3j, txnManager, gasProvider)
    bridgeContract.send_sendNative(recipient.publicKeyHash, amountInEther.bigInteger).encodeFunctionCall()
  }
}

object ElNativeBridgeClient {
  val BurnAddress = EthereumConstants.ZeroAddress
}
