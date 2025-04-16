package units.el

import com.wavesplatform.utils.ScorexLogging
import org.web3j.crypto.RawTransaction
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import units.bridge.ERC20
import units.docker.EcContainer
import units.eth.EthAddress

import java.math.BigInteger

class Erc20Client(
    web3j: Web3j,
    erc20Address: EthAddress,
    txnManager: RawTransactionManager,
    gasProvider: DefaultGasProvider = new DefaultGasProvider
) extends ScorexLogging {
  private lazy val contract = ERC20.load(erc20Address.hex, web3j, txnManager, gasProvider)

  def sendApprove(spender: EthAddress, amount: BigInt): EthSendTransaction =
    sendApprove(
      spender,
      amount,
      web3j.ethGetTransactionCount(txnManager.getFromAddress, DefaultBlockParameterName.PENDING).send().getTransactionCount.intValueExact()
    )

  def sendApprove(spender: EthAddress, amount: BigInt, nonce: Int): EthSendTransaction = {
    val to       = contract.getContractAddress
    val funcCall = contract.send_approve(spender.hex, amount.bigInteger).encodeFunctionCall()
    val rawTxn = RawTransaction.createTransaction(
      EcContainer.ChainId,
      BigInteger.valueOf(nonce),
      gasProvider.getGasLimit,
      to,
      BigInteger.ZERO,
      funcCall,
      BigInteger.ONE,
      gasProvider.getGasPrice
    )

    log.debug(s"Send ${txnManager.getFromAddress} approval for ${spender.hex}, nonce: $nonce")
    val r = txnManager.signAndSend(rawTxn)
    if (r.hasError) throw new TransactionException(s"Can't send approve: ${r.getError}, ${r.getError.getMessage}")
    r
  }

  def getBalance(account: EthAddress): BigInt = contract.call_balanceOf(account.hex).send()
  def totalSupply: BigInt                     = contract.call_totalSupply().send()
}
