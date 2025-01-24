package units.el

import com.wavesplatform.account.Address
import com.wavesplatform.transaction.utils.EthConverters.EthereumAddressExt
import org.web3j.abi.datatypes.generated.{Bytes20, Int64}
import org.web3j.abi.datatypes.{Event, Function, Address as Web3JAddress}
import org.web3j.abi.{FunctionEncoder, FunctionReturnDecoder, TypeEncoder, TypeReference}
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

  case class ERC20BridgeInitiated(wavesRecipient: Address, clAmount: Long, assetAddress: EthAddress)
  object ERC20BridgeInitiated extends BridgeMerkleTree[ERC20BridgeInitiated] {
    override val exactTransfersNumber = 1024

    private val WavesRecipientType = new TypeReference[Bytes20](false) {}
    private val ClAmountType       = new TypeReference[Int64](false) {}
    private val AssetAddressType   = new TypeReference[Web3JAddress](false) {}

    private val EventDef: Event = new Event(
      "ERC20BridgeInitiated",
      List[TypeReference[?]](WavesRecipientType, ClAmountType, AssetAddressType).asJava
    )

    val Topic = org.web3j.abi.EventEncoder.encode(EventDef)

    override def encodeArgs(args: ERC20BridgeInitiated): Array[Byte] = {
      val wavesRecipient = new Bytes20(args.wavesRecipient.publicKeyHash)
      require(wavesRecipient.getClass == WavesRecipientType.getClassType) // Make sure types are correct

      val clAmount = new Int64(args.clAmount)
      require(clAmount.getClass == ClAmountType.getClassType)

      val assetAddress = new Web3JAddress(args.assetAddress.hex)
      require(assetAddress.getClass == AssetAddressType.getClassType)

      HexBytesConverter.toBytes(TypeEncoder.encode(wavesRecipient) + TypeEncoder.encode(clAmount) + TypeEncoder.encode(assetAddress))
    }

    override def decodeLog(ethEventData: String): Either[String, ERC20BridgeInitiated] =
      try {
        FunctionReturnDecoder.decode(ethEventData, EventDef.getNonIndexedParameters).asScala.toList match {
          case (wavesRecipient: Bytes20) :: (clAmount: Int64) :: (assetAddress: Web3JAddress) :: Nil =>
            for {
              wavesRecipient <- Try(Address(wavesRecipient.getValue)).toEither.left.map(e => s"Can't decode address: ${e.getMessage}")
              clAmount       <- Try(clAmount.getValue.longValueExact()).toEither.left.map(e => s"Can't decode amount: ${e.getMessage}")
              _              <- Either.cond(clAmount > 0, (), s"Transfer amount must be positive, got: $clAmount")
              assetAddress   <- EthAddress.from(assetAddress.getValue)
            } yield new ERC20BridgeInitiated(wavesRecipient, clAmount, assetAddress)
          case xs =>
            Left(
              s"Expected (wavesRecipient: ${WavesRecipientType.getClassType.getSimpleName}, elAmount: ${ClAmountType.getClassType.getSimpleName}) fields, " +
                s"got: ${xs.mkString(", ")}"
            )
        }
      } catch {
        case NonFatal(e) => Left(s"Can't decode event: ${e.getMessage}, data=$ethEventData")
      }
  }

  case class ERC20BridgeFinalized(recipient: EthAddress, clAmount: Long, assetAddress: EthAddress)
  object ERC20BridgeFinalized {
    private val RecipientType    = new TypeReference[Web3JAddress](false) {}
    private val ClAmountType     = new TypeReference[Int64](false) {}
    private val AssetAddressType = new TypeReference[Web3JAddress](false) {}

    private val EventDef: Event = new Event(
      "ERC20BridgeFinalized",
      List[TypeReference[?]](RecipientType, ClAmountType, AssetAddressType).asJava
    )

    val Topic = org.web3j.abi.EventEncoder.encode(EventDef)

    def encodeArgs(args: ERC20BridgeFinalized): String = {
      val recipient = new Web3JAddress(args.recipient.hex)
      require(recipient.getClass == RecipientType.getClassType) // Make sure types are correct

      val amount = new Int64(args.clAmount)
      require(amount.getClass == ClAmountType.getClassType)

      val assetAddress = new Web3JAddress(args.assetAddress.hex)
      require(assetAddress.getClass == AssetAddressType.getClassType)

      TypeEncoder.encode(recipient) + TypeEncoder.encode(amount) + TypeEncoder.encode(assetAddress)
    }

    def decodeLog(ethEventData: String): Either[String, ERC20BridgeFinalized] =
      try {
        FunctionReturnDecoder.decode(ethEventData, EventDef.getNonIndexedParameters).asScala.toList match {
          case (recipient: Web3JAddress) :: (rawReceivedAmount: Int64) :: (assetAddress: Web3JAddress) :: Nil =>
            for {
              recipient    <- EthAddress.from(recipient.getValue)
              amount       <- Try(rawReceivedAmount.getValue.longValueExact()).toEither.left.map(e => s"Can't decode amount: ${e.getMessage}")
              _            <- Either.cond(amount > 0, amount, s"Transfer amount must be positive, got: $amount")
              assetAddress <- EthAddress.from(assetAddress.getValue)
            } yield new ERC20BridgeFinalized(recipient, amount, assetAddress)
          case xs =>
            Left(
              s"Expected (recipient: ${RecipientType.getClassType.getSimpleName}, clAmount: ${ClAmountType.getClassType.getSimpleName}) fields, " +
                s"got: ${xs.mkString(", ")}"
            )
        }
      } catch {
        case NonFatal(e) => Left(s"Can't decode event: ${e.getMessage}, data=$ethEventData")
      }
  }

  // See https://specs.optimism.io/protocol/deposits.html#execution
  def mkDepositedTransaction(
      transferIndex: Long,
      elContractAddress: EthAddress,
      sender: Address,
      recipient: EthAddress,
      amountInWaves: Long
  ): DepositedTransaction =
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
        new org.web3j.abi.datatypes.Address(receiver.hexNoPrefix),
        new org.web3j.abi.datatypes.generated.Int64(amount)
      ),
      Collections.emptyList
    )
    FunctionEncoder.encode(function)
  }
}
