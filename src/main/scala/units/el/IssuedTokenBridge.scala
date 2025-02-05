package units.el

import com.wavesplatform.account.Address
import org.web3j.abi.*
import org.web3j.abi.datatypes.generated.{Bytes20, Int64, Uint8}
import org.web3j.abi.datatypes.{Event, Function, Type, Address as Web3JAddress, DynamicArray as Web3JArray}
import units.eth.{EthAddress, EthereumConstants}
import units.util.HexBytesConverter

import java.math.BigInteger
import java.util
import java.util.Collections
import scala.jdk.CollectionConverters.{CollectionHasAsScala, SeqHasAsJava}
import scala.util.Try
import scala.util.control.NonFatal

object IssuedTokenBridge {
  val FinalizeBridgeErc20Function = "finalizeBridgeERC20"
  val FinalizeBridgeErc20Gas      = BigInteger.valueOf(100_000L) // Should be enough to run this function

  val UpdateTokenRegistryFunction = "updateTokenRegistry"
  val UpdateTokenRegistryGas      = BigInteger.valueOf(500_000L)

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

    val Topic = EventEncoder.encode(EventDef)

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
              s"Expected (wavesRecipient: ${WavesRecipientType.getClassType.getSimpleName}, elAmount: ${ClAmountType.getClassType.getSimpleName}," +
                s"assetAddress: ${AssetAddressType.getClassType.getSimpleName}) fields, got: ${xs.mkString(", ")}"
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

    val Topic = EventEncoder.encode(EventDef)

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
              s"Expected (recipient: ${RecipientType.getClassType.getSimpleName}, clAmount: ${ClAmountType.getClassType.getSimpleName}, " +
                s"assetAddress: ${AssetAddressType.getClassType.getSimpleName}) fields, got: ${xs.mkString(", ")}"
            )
        }
      } catch {
        case NonFatal(e) => Left(s"Can't decode event: ${e.getMessage}, data=$ethEventData")
      }
  }

  case class RegistryUpdated(added: List[EthAddress], addedExponents: List[Int], removed: List[EthAddress])
  object RegistryUpdated {
    private val AddressesType = new TypeReference[Web3JArray[Web3JAddress]](false) {}
    private val ExponentType  = new TypeReference[Web3JArray[Uint8]](false) {}

    private val EventDef: Event = new Event(
      "RegistryUpdated",
      List[TypeReference[?]](AddressesType, ExponentType, AddressesType).asJava
    )

    val Topic = EventEncoder.encode(EventDef)

    def encodeArgs(args: RegistryUpdated): String = {
      val added          = new Web3JArray(classOf[Web3JAddress], args.added.map(x => new Web3JAddress(x.hexNoPrefix)).asJava)
      val addedExponents = new Web3JArray(classOf[Uint8], args.addedExponents.map(new Uint8(_)).asJava)
      val removed        = new Web3JArray(classOf[Web3JAddress], args.removed.map(x => new Web3JAddress(x.hexNoPrefix)).asJava)

      TypeEncoder.encode(added) + TypeEncoder.encode(addedExponents) + TypeEncoder.encode(removed)
    }

    def decodeLog(ethEventData: String): Either[String, RegistryUpdated] =
      try {
        FunctionReturnDecoder.decode(ethEventData, EventDef.getNonIndexedParameters).asScala.toList match {
          case (added: Web3JArray[Web3JAddress @unchecked]) ::
              (addedExponents: Web3JArray[Uint8 @unchecked]) ::
              (removed: Web3JArray[Web3JAddress @unchecked]) :: Nil
              if added.getComponentType == classOf[Web3JAddress] &&
                addedExponents.getComponentType == classOf[Uint8] &&
                removed.getComponentType == classOf[Web3JAddress] =>
            for {
              added <- Try(added.getValue.asScala.map(x => EthAddress.unsafeFrom(x.getValue)).toList).toEither.left.map(e =>
                s"Can't decode added field: ${e.getMessage}"
              )
              addedExponents <- Try(addedExponents.getValue.asScala.map(_.getValue.intValueExact()).toList).toEither.left.map(e =>
                s"Can't decode addedExponents field: ${e.getMessage}"
              )
              removed <- Try(removed.getValue.asScala.map(x => EthAddress.unsafeFrom(x.getValue)).toList).toEither.left.map(e =>
                s"Can't decode removed field: ${e.getMessage}"
              )
            } yield new RegistryUpdated(added, addedExponents, removed)
          case xs =>
            Left(
              s"Expected (added: ${AddressesType.getClassType.getSimpleName}, addedExponents: ${ExponentType.getClassType.getSimpleName}, " +
                s"removed: ${AddressesType.getClassType.getSimpleName}) fields, got: ${xs.mkString(", ")}"
            )
        }
      } catch {
        case NonFatal(e) => Left(s"Can't decode event: ${e.getMessage}, data=$ethEventData")
      }
  }

  // See https://specs.optimism.io/protocol/deposits.html#execution
  def mkFinalizeBridgeErc20Transaction(
      transferIndex: Long,
      elContractAddress: EthAddress,
      recipient: EthAddress,
      amountInWaves: Long
  ): DepositedTransaction =
    DepositedTransaction.create(
      sourceHash = DepositedTransaction.mkUserDepositedSourceHash(transferIndex),
      from = EthereumConstants.ZeroAddress.hexNoPrefix,
      to = elContractAddress.hex,
      mint = BigInteger.ZERO,
      value = BigInteger.ZERO,
      gas = FinalizeBridgeErc20Gas,
      isSystemTx = true, // Gas won't be consumed
      data = HexBytesConverter.toBytes(finalizeBridgeErc20Call(recipient, amountInWaves))
    )

  def finalizeBridgeErc20Call(receiver: EthAddress, amount: Long): String = {
    val function = new Function(
      FinalizeBridgeErc20Function,
      util.Arrays.asList[Type[?]](
        new Web3JAddress(receiver.hexNoPrefix),
        new Int64(amount)
      ),
      Collections.emptyList
    )
    FunctionEncoder.encode(function)
  }

  def mkUpdateTokenRegistry(added: List[EthAddress], addedAssetExponents: List[Int], elBridgeAddress: EthAddress): DepositedTransaction =
    DepositedTransaction.create(
      sourceHash = DepositedTransaction.mkUserDepositedSourceHash(HexBytesConverter.toBytes(added.last.hex)), // TODO
      from = EthereumConstants.ZeroAddress.hexNoPrefix,
      to = elBridgeAddress.hex,
      mint = BigInteger.ZERO,
      value = BigInteger.ZERO,
      gas = UpdateTokenRegistryGas,
      isSystemTx = true, // Gas won't be consumed
      data = HexBytesConverter.toBytes(updateTokenRegistryCall(added, addedAssetExponents))
    )

  def updateTokenRegistryCall(added: List[EthAddress], addedAssetExponents: List[Int]): String = {
    val function = new Function(
      UpdateTokenRegistryFunction,
      util.Arrays.asList[Type[?]](
        new Web3JArray(classOf[Web3JAddress], added.map(x => new Web3JAddress(x.hexNoPrefix))*),
        new Web3JArray(classOf[Uint8], addedAssetExponents.map(new Uint8(_))*)
      ),
      Collections.emptyList
    )
    FunctionEncoder.encode(function)
  }
}
