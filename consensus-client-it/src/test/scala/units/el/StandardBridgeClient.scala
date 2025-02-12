package units.el

import com.wavesplatform.account.Address
import com.wavesplatform.utils.{EthEncoding, ScorexLogging}
import org.web3j.crypto.{Credentials, RawTransaction}
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import units.bridge.StandardBridge
import units.docker.EcContainer
import units.eth.EthAddress

import java.math.BigInteger

class StandardBridgeClient(
    web3j: Web3j,
    standardBridgeAddress: EthAddress,
    defaultSender: Credentials,
    gasProvider: DefaultGasProvider = new DefaultGasProvider
) extends ScorexLogging {
  def sendBridgeErc20(
      sender: Credentials,
      token: EthAddress,
      clTo: Address,
      elAmount: BigInt
  ): EthSendTransaction = {
    val senderAddress = sender.getAddress
    val txnManager    = new RawTransactionManager(web3j, sender, EcContainer.ChainId)
    val funcCall      = getBridgeErc20FunctionCall(sender, token, clTo, elAmount)
    val nonce = web3j.ethGetTransactionCount(senderAddress, DefaultBlockParameterName.PENDING).send().getTransactionCount
    
    val res = web3j.ethCall(new Transaction(
      sender.getAddress,
      nonce,
      gasProvider.getGasPrice,
      gasProvider.getGasLimit,
      token.toString,
      BigInteger.ZERO,
      funcCall
    ), DefaultBlockParameterName.LATEST).send()
    
//    Thread.sleep(1000_000)

    
    val rawTxn = RawTransaction.createTransaction(
      nonce,
      gasProvider.getGasPrice,
      gasProvider.getGasLimit,
      standardBridgeAddress.hex,
      BigInteger.ZERO,
      funcCall
    )

    log.debug(s"Send bridgeERC20($senderAddress->$clTo: $elAmount of $token), nonce: $nonce")
    val r = txnManager.signAndSend(rawTxn)
    if (r.hasError) throw new TransactionException(s"Can't send bridgeERC20: ${r.getError}, ${r.getError.getMessage}")
    r
  }

  def getRevertReasonForBridgeErc20(
      sender: Credentials,
      token: EthAddress,
      clTo: Address,
      elAmount: BigInt
  ): String = {
    val senderAddress = sender.getAddress
    val funcCall      = getBridgeErc20FunctionCall(sender, token, clTo, elAmount)
    val txn           = Transaction.createEthCallTransaction(senderAddress, standardBridgeAddress.hex, funcCall, BigInteger.ZERO)

    log.debug(s"Call bridgeERC20($senderAddress->$clTo: $elAmount of $token)")
    val r = web3j.ethCall(txn, DefaultBlockParameterName.PENDING).send()
    if (r.isReverted) r.getRevertReason
    else throw new TransactionException(s"Call bridgeERC20: expected $txn to be reverted")
  }

  def getBridgeErc20FunctionCall(
      sender: Credentials,
      token: EthAddress,
      clTo: Address,
      elAmount: BigInt
  ): String = {
    val txnManager = new RawTransactionManager(web3j, sender, EcContainer.ChainId)
    val contract   = StandardBridge.load(standardBridgeAddress.hex, web3j, txnManager, gasProvider) // TODO move to class?
    contract.send_bridgeERC20(token.hexNoPrefix, EthEncoding.toHexString(clTo.publicKeyHash), elAmount.bigInteger).encodeFunctionCall()
  }

  def isRegistered(assetAddress: EthAddress): Boolean = {
    val txnManager = new RawTransactionManager(web3j, defaultSender, EcContainer.ChainId)
    val contract   = StandardBridge.load(standardBridgeAddress.hex, web3j, txnManager, gasProvider)
    val ratio      = contract.call_tokenRatios(assetAddress.hexNoPrefix).send()
    ratio != BigInteger.ZERO
  }

  def getBalance(of: EthAddress): BigInt = {
    val txnManager = new RawTransactionManager(web3j, defaultSender, EcContainer.ChainId)
    val contract   = StandardBridge.load(standardBridgeAddress.hex, web3j, txnManager, gasProvider)
    contract.call_balances(of.hex).send()
  }
}
