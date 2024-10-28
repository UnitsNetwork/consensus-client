package units.el

import com.wavesplatform.account.Address
import com.wavesplatform.utils.{EthEncoding, ScorexLogging}
import org.web3j.abi.datatypes.generated.Bytes20
import org.web3j.abi.datatypes.{Type, Function as Web3Function}
import org.web3j.crypto.{Credentials, RawTransaction, TransactionEncoder}
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.utils.Numeric
import units.eth.EthAddress

import java.util.concurrent.ThreadLocalRandom
import scala.jdk.CollectionConverters.SeqHasAsJava

class ElBridgeClient(web3j: Web3j, val address: EthAddress) extends ScorexLogging {
  def sendNative(sender: Credentials, recipient: Address, amountInEther: BigInt): EthSendTransaction = {
    val currRequestId = ThreadLocalRandom.current().nextInt(10000, 100000).toString

    val senderAddress = sender.getAddress
    log.debug(s"[$currRequestId] sendNative($senderAddress->$recipient: $amountInEther Ether)")

    val recipientAddressHex = Numeric.toHexString(recipient.publicKeyHash)
    val data = org.web3j.abi.FunctionEncoder.encode(
      new Web3Function(
        "sendNative",
        List[Type[?]](new Bytes20(EthEncoding.toBytes(recipientAddressHex))).asJava,
        List.empty.asJava
      )
    )

    val ethGetTransactionCount = web3j
      .ethGetTransactionCount(senderAddress, DefaultBlockParameterName.LATEST)
      .send()
    val nonce = ethGetTransactionCount.getTransactionCount

    val transaction = RawTransaction.createTransaction(
      nonce,
      DefaultGasProvider.GAS_PRICE,
      DefaultGasProvider.GAS_LIMIT,
      address.hex,
      amountInEther.bigInteger,
      data
    )

    val signedMessage = TransactionEncoder.signMessage(transaction, sender)
    val hexValue      = Numeric.toHexString(signedMessage)
    val r             = web3j.ethSendRawTransaction(hexValue).send()

    log.debug(s"[$currRequestId] txn=${r.getTransactionHash}")
    r
  }

}
