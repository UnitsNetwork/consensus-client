package units.el

import com.typesafe.scalalogging.StrictLogging
import org.web3j.crypto.RawTransaction
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import units.bridge.TERC20
import units.docker.EcContainer
import units.eth.EthAddress

import java.math.BigInteger

class TERC20Client(
    web3j: Web3j,
    erc20Address: EthAddress,
    txnManager: RawTransactionManager,
    gasProvider: DefaultGasProvider = new DefaultGasProvider
) extends StrictLogging {
  private lazy val contract = TERC20.load(erc20Address.hex, web3j, txnManager, gasProvider)

  def sendBurn(spender: EthAddress, amount: BigInt, nonce: Int): EthSendTransaction = {
    val to = contract.getContractAddress
    val funcCall = contract.send_burn(spender.hex, amount.bigInteger).encodeFunctionCall()
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

    logger.debug(s"Send ${txnManager.getFromAddress} burn for ${spender.hex}, nonce: $nonce")
    val r = txnManager.signAndSend(rawTxn)
    if (r.hasError) throw new TransactionException(s"Can't send burn: ${r.getError}, ${r.getError.getMessage}")
    r
  }
}
