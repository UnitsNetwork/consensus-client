package units.el

import com.wavesplatform.account.Address
import com.wavesplatform.utils.ScorexLogging
import org.web3j.abi.datatypes.{AbiTypes, Type}
import org.web3j.abi.{FunctionReturnDecoder, TypeReference}
import org.web3j.crypto.{Credentials, RawTransaction}
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import units.bridge.BridgeContract
import units.docker.EcContainer
import units.eth.EthAddress

import java.util.Collections

class ElBridgeClient(web3j: Web3j, address: EthAddress) extends ScorexLogging {
  def sendNative(
      sender: Credentials,
      recipient: Address,
      amountInEther: BigInt
  ): EthSendTransaction = {
    val senderAddress = sender.getAddress
    log.debug(s"sendNative($senderAddress->$recipient: $amountInEther Wei)")
    val txnManager     = new RawTransactionManager(web3j, sender, EcContainer.ChainId)
    val gasProvider    = new DefaultGasProvider
    val bridgeContract = BridgeContract.load(address.hex, web3j, txnManager, gasProvider)
    val funcCall       = bridgeContract.send_sendNative(recipient.publicKeyHash, amountInEther.bigInteger).encodeFunctionCall()

    val nonce = web3j.ethGetTransactionCount(address.hex, DefaultBlockParameterName.LATEST).send().getTransactionCount
    val rawTxn = RawTransaction.createTransaction(
      nonce,
      gasProvider.getGasPrice,
      gasProvider.getGasLimit,
      address.hex,
      amountInEther.bigInteger,
      funcCall
    )

    txnManager.signAndSend(rawTxn)
  }
}

object ElBridgeClient {
  val BurnAddress = EthAddress.unsafeFrom("0x0000000000000000000000000000000000000000")

  def decodeRevertReason(hexRevert: String): String =
    if (Option(hexRevert).isEmpty) "???"
    else {
      val cleanHex       = if (hexRevert.startsWith("0x")) hexRevert.drop(2) else hexRevert
      val errorSignature = "08c379a0" // Error(string)

      if (!cleanHex.startsWith(errorSignature)) throw new RuntimeException(s"Not a revert reason: $hexRevert")

      val strType           = TypeReference.create(AbiTypes.getType("string").asInstanceOf[Class[Type[?]]])
      val revertReasonTypes = Collections.singletonList(strType)

      val encodedReason = "0x" + cleanHex.drop(8)
      val decoded       = FunctionReturnDecoder.decode(encodedReason, revertReasonTypes)
      if (decoded.isEmpty) throw new RuntimeException(s"Unknown revert reason: $hexRevert")
      else decoded.get(0).getValue.asInstanceOf[String]
    }
}
