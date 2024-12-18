package units.el

import com.wavesplatform.account.Address
import com.wavesplatform.transaction.utils.EthConverters.EthereumAddressExt
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.{Event, Function, Address as Web3JAddress}
import org.web3j.abi.{FunctionEncoder, FunctionReturnDecoder, TypeReference}
import units.eth.EthAddress
import units.util.HexBytesConverter

import java.math.BigInteger
import java.util
import java.util.Collections
import scala.jdk.CollectionConverters.{CollectionHasAsScala, SeqHasAsJava}
import scala.util.Try
import scala.util.control.NonFatal

object IssuedTokenBridge {
  val ReceiveIssuedFunction = "receiveIssued"
  val ReceiveIssuedGas      = BigInteger.valueOf(100_000L) // Should be enough to run this function

  // TODO move to tests?
  case class ElReceivedIssuedEvent(recipient: EthAddress, amount: BigInt)
  object ElReceivedIssuedEvent {
    private val RecipientType = new TypeReference[Web3JAddress](false) {}
    private val ElAmountType  = new TypeReference[Uint256](false) {}

    private val EventDef: Event = new Event(
      "ReceivedIssued",
      List[TypeReference[?]](RecipientType, ElAmountType).asJava
    )

    val Topic = org.web3j.abi.EventEncoder.encode(EventDef)

    def decodeArgs(ethEventData: String): Either[String, ElReceivedIssuedEvent] =
      try {
        FunctionReturnDecoder.decode(ethEventData, EventDef.getNonIndexedParameters).asScala.toList match {
          case (recipient: Web3JAddress) :: (rawReceivedAmount: Uint256) :: Nil =>
            for {
              elRecipient <- Try(EthAddress.unsafeFrom(recipient.getValue)).toEither.left.map(e => s"Can't decode address: ${e.getMessage}")
              amount      <- Try(BigInt(rawReceivedAmount.getValue)).toEither.left.map(e => s"Can't decode amount: ${e.getMessage}")
              _           <- Either.cond(amount > 0, amount, s"Transfer amount must be positive, got: $amount")
            } yield new ElReceivedIssuedEvent(elRecipient, amount)
          case xs =>
            Left(
              s"Expected (recipient: ${RecipientType.getClassType.getSimpleName}, elAmount: ${ElAmountType.getClassType.getSimpleName}) fields, got: ${xs
                .mkString(", ")}"
            )
        }
      } catch {
        case NonFatal(e) => Left(s"Can't decode event: ${e.getMessage}")
      }
  }

  // See https://specs.optimism.io/protocol/deposits.html#execution
  def mkDepositTransaction(transferIndex: Long, elContractAddress: EthAddress, sender: Address, recipient: EthAddress, amountInWaves: Long): DepositedTransaction =
    DepositedTransaction.create(
      sourceHash = DepositedTransaction.mkUserDepositedSourceHash(transferIndex),
      from = sender.toEthAddress,
      to = elContractAddress.hex,
      mint = BigInteger.ZERO,
      value = BigInteger.ZERO,
      gas = ReceiveIssuedGas,
      isSystemTx = true, // Gas won't be consumed
      data = HexBytesConverter.toBytes(receiveIssuedCall(recipient, amountInWaves))
    )

  def receiveIssuedCall(receiver: EthAddress, amount: Long): String = {
    val function = new Function(
      ReceiveIssuedFunction,
      util.Arrays.asList[org.web3j.abi.datatypes.Type[?]](
        new org.web3j.abi.datatypes.Address(160, receiver.hexNoPrefix),
        new org.web3j.abi.datatypes.generated.Int64(amount)
      ),
      Collections.emptyList
    )
    FunctionEncoder.encode(function)
  }
}
