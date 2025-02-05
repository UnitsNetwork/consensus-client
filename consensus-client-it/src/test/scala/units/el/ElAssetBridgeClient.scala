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
import units.bridge.IssuedTokenBridgeContract
import units.docker.EcContainer
import units.eth.EthAddress

import java.math.BigInteger

class ElAssetBridgeClient(
    web3j: Web3j,
    address: EthAddress,
    defaultSender: Credentials,
    gasProvider: DefaultGasProvider = new DefaultGasProvider
) extends ScorexLogging {
  def sendBridge(
      sender: Credentials,
      recipient: Address,
      amountInEther: BigInt
  ): EthSendTransaction = {
    val senderAddress = sender.getAddress
    val txnManager    = new RawTransactionManager(web3j, sender, EcContainer.ChainId)
    val funcCall      = getBridgeFunctionCall(sender, recipient, amountInEther)

    val nonce = web3j.ethGetTransactionCount(senderAddress, DefaultBlockParameterName.PENDING).send().getTransactionCount
    val rawTxn = RawTransaction.createTransaction(
      nonce,
      gasProvider.getGasPrice,
      gasProvider.getGasLimit,
      address.hex,
      BigInteger.ZERO,
      funcCall
    )

    log.debug(s"Send bridgeERC20($senderAddress->$recipient: $amountInEther Wei), nonce: $nonce")
    val r = txnManager.signAndSend(rawTxn)
    if (r.hasError) throw new TransactionException(s"Can't call sendIssued: ${r.getError}, ${r.getError.getMessage}")
    r
  }

  def getRevertReasonForBridge(
      sender: Credentials,
      recipient: Address,
      amountInEther: BigInt
  ): String = {
    val senderAddress = sender.getAddress
    val funcCall      = getBridgeFunctionCall(sender, recipient, amountInEther)
    val txn           = Transaction.createEthCallTransaction(senderAddress, address.hex, funcCall, BigInteger.ZERO)

    log.debug(s"Call bridgeERC20($senderAddress->$recipient: $amountInEther Wei)")
    val r = web3j.ethCall(txn, DefaultBlockParameterName.PENDING).send()
    if (r.isReverted) r.getRevertReason
    else throw new TransactionException(s"Expected $txn to be reverted")
  }

  def getBridgeFunctionCall(
      sender: Credentials,
      recipient: Address,
      amountInEther: BigInt
  ): String = {
    val txnManager     = new RawTransactionManager(web3j, sender, EcContainer.ChainId)
    val bridgeContract = IssuedTokenBridgeContract.load(address.hex, web3j, txnManager, gasProvider) // TODO move to class?
    // TODO Specify asset
    bridgeContract.send_bridgeERC20(recipient.publicKeyHash, amountInEther.bigInteger, address.hexNoPrefix).encodeFunctionCall()
  }

  def tokensRatio(assetAddress: EthAddress): Option[Long] = {
    val txnManager     = new RawTransactionManager(web3j, defaultSender, EcContainer.ChainId)
    val bridgeContract = IssuedTokenBridgeContract.load(address.hex, web3j, txnManager, gasProvider)
    val r = bridgeContract.call_tokensRatio(assetAddress.hexNoPrefix).send().getValue
    if (r == BigInteger.ZERO) None else Some(r.longValueExact())
  }

  def sendMint(
      sender: Credentials,
      recipient: EthAddress,
      amountInEther: BigInt
  ): EthSendTransaction = {
    val senderAddress = sender.getAddress
    val txnManager    = new RawTransactionManager(web3j, sender, EcContainer.ChainId)
    val funcCall      = getMintFunctionCall(sender, recipient, amountInEther)

    val nonce = web3j.ethGetTransactionCount(senderAddress, DefaultBlockParameterName.PENDING).send().getTransactionCount
    val rawTxn = RawTransaction.createTransaction(
      nonce,
      gasProvider.getGasPrice,
      gasProvider.getGasLimit,
      address.hex,
      BigInteger.ZERO,
      funcCall
    )

    log.debug(s"Send mint($senderAddress->$recipient: $amountInEther Wei), nonce: $nonce")
    val r = txnManager.signAndSend(rawTxn)
    if (r.hasError) throw new TransactionException(s"Can't call mint: ${r.getError}, ${r.getError.getMessage}")
    r
  }

  def getMintFunctionCall(
      sender: Credentials,
      recipient: EthAddress,
      amountInEther: BigInt
  ): String = {
    val txnManager     = new RawTransactionManager(web3j, sender, EcContainer.ChainId)
    val bridgeContract = IssuedTokenBridgeContract.load(address.hex, web3j, txnManager, gasProvider)
    bridgeContract.send_mint(recipient.hex, amountInEther.bigInteger).encodeFunctionCall()
  }

  def getBalance(of: EthAddress): BigInt = {
    val txnManager     = new RawTransactionManager(web3j, defaultSender, EcContainer.ChainId)
    val bridgeContract = IssuedTokenBridgeContract.load(address.hex, web3j, txnManager, gasProvider)
    bridgeContract.call_balances(of.hex).send()
  }
}
