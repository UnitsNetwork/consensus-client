package units.el

import com.wavesplatform.account.Address
import com.wavesplatform.api.{HasRetry, LoggingUtil}
import com.wavesplatform.utils.{EthEncoding, ScorexLogging}
import org.scalatest.Assertions.fail
import org.web3j.abi.datatypes.generated.Bytes20
import org.web3j.abi.datatypes.{Type, Function as Web3Function}
import org.web3j.crypto.{Credentials, RawTransaction, TransactionEncoder}
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.{EthSendTransaction, TransactionReceipt}
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.utils.Numeric
import units.eth.EthAddress

import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.jdk.OptionConverters.RichOptional
import scala.util.chaining.scalaUtilChainingOps

class ElBridgeClient(web3j: Web3j, val address: EthAddress) extends HasRetry with ScorexLogging {
  def sendNativeAndWait(
      sender: Credentials,
      recipient: Address,
      amountInEther: BigInt
  ): TransactionReceipt = {
    val baseRequestId = LoggingUtil.currRequestId
    val senderAddress = sender.getAddress
    log.debug(s"[$baseRequestId] sendNativeAndWait($senderAddress->$recipient: $amountInEther Ether)")
    val sendTxnResult = sendNativeImpl(sender, recipient, amountInEther, ElBridgeClient.currRequestId(baseRequestId))
    retryWithAttempts { attempt =>
      web3j
        .ethGetTransactionReceipt(sendTxnResult.getTransactionHash)
        .tap(_.setId(ElBridgeClient.currRequestId(baseRequestId, attempt)))
        .send()
        .getTransactionReceipt
        .toScala
        .getOrElse(fail(s"No receipt for ${sendTxnResult.getTransactionHash}"))
    }
  }

  protected def sendNativeImpl(
      sender: Credentials,
      recipient: Address,
      amountInEther: BigInt,
      currRequestId: Long
  ): EthSendTransaction = {
    val senderAddress       = sender.getAddress
    val recipientAddressHex = Numeric.toHexString(recipient.publicKeyHash)
    val data = org.web3j.abi.FunctionEncoder.encode(
      new Web3Function(
        "sendNative",
        List[Type[?]](new Bytes20(EthEncoding.toBytes(recipientAddressHex))).asJava,
        List.empty.asJava
      )
    )

    val ethGetTransactionCount = web3j.ethGetTransactionCount(senderAddress, DefaultBlockParameterName.LATEST).tap(_.setId(currRequestId)).send()
    val nonce                  = ethGetTransactionCount.getTransactionCount

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
    val r             = web3j.ethSendRawTransaction(hexValue).tap(_.setId(currRequestId)).send()

    log.debug(s"[${ElBridgeClient.baseId(currRequestId)}] txn=${r.getTransactionHash}")
    r
  }
}

object ElBridgeClient {
  val AttemptIdLength = 3 // 001-999

  // HACK: Distinguish the first request (001) and retries (002-999)
  def currRequestId(base: Int = LoggingUtil.currRequestId, attempt: Int = 1): Long = base * 1000L + attempt

  def idHasAttempt(id: Long): Boolean = id.toString.length == (LoggingUtil.Length + ElBridgeClient.AttemptIdLength)
  def baseId(requestId: Long): Int    = (requestId / 1000).toInt
  def attempt(requestId: Long): Int   = (requestId % 1000).toInt
}
