package units.el

import com.wavesplatform.account.Address
import com.wavesplatform.api.HasRetry
import com.wavesplatform.utils.ScorexLogging
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.gas.DefaultGasProvider
import units.bridge.BridgeContract
import units.eth.EthAddress

class ElBridgeClient(web3j: Web3j, val address: EthAddress) extends HasRetry with ScorexLogging {
  def sendNativeAndWait(
      sender: Credentials,
      recipient: Address,
      amountInEther: BigInt
  ): TransactionReceipt = {
    val senderAddress = sender.getAddress
    log.debug(s"endNativeAndWait($senderAddress->$recipient: $amountInEther Ether)")
    val bridgeContract = BridgeContract.load(address.hex, web3j, sender, new DefaultGasProvider)
    bridgeContract.send_sendNative(recipient.publicKeyHash, amountInEther.bigInteger).send()
  }
}
