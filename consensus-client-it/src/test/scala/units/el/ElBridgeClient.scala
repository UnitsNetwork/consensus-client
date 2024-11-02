package units.el

import com.wavesplatform.account.Address
import com.wavesplatform.api.HasRetry
import com.wavesplatform.utils.ScorexLogging
import org.web3j.abi.datatypes.{AbiTypes, Type}
import org.web3j.abi.{FunctionReturnDecoder, TypeReference}
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.gas.DefaultGasProvider
import units.bridge.BridgeContract
import units.eth.EthAddress

import java.util.Collections

class ElBridgeClient(web3j: Web3j, val address: EthAddress) extends HasRetry with ScorexLogging {
  def sendNative(
      sender: Credentials,
      recipient: Address,
      amountInEther: BigInt
  ): TransactionReceipt = {
    val senderAddress = sender.getAddress
    log.debug(s"sendNative($senderAddress->$recipient: $amountInEther Ether)")
    val bridgeContract = BridgeContract.load(address.hex, web3j, sender, new DefaultGasProvider)
    bridgeContract.send_sendNative(recipient.publicKeyHash, amountInEther.bigInteger).send()
  }
}

object ElBridgeClient {
  val BurnAddress = EthAddress.unsafeFrom("0x0000000000000000000000000000000000000000")

  def decodeRevertReason(hexRevert: String): String = {
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
