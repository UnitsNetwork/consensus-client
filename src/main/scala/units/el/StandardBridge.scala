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

object StandardBridge {
  val FinalizeBridgeErc20Function = "finalizeBridgeERC20"
  val FinalizeBridgeErc20Gas      = BigInteger.valueOf(100_000L) // Should be enough to run this function

  val UpdateAssetRegistryFunction = "updateAssetRegistry"
  val UpdateAssetRegistryGas      = BigInteger.valueOf(500_000L)

  case class ERC20BridgeInitiated(localToken: EthAddress, clTo: Address, clAmount: Long)
  object ERC20BridgeInitiated extends BridgeMerkleTree[ERC20BridgeInitiated] {
    override val exactTransfersNumber = 1024

    type LocalTokenType = Web3JAddress
    type ClToType       = Bytes20
    type ClAmountType   = Int64

    private val EventDef: Event = new Event(
      "ERC20BridgeInitiated",
      List[TypeReference[?]](
        new TypeReference[LocalTokenType](false) {},
        new TypeReference[ClToType](false)       {},
        new TypeReference[ClAmountType](false)   {}
      ).asJava
    )

    val Topic = EventEncoder.encode(EventDef)

    override def encodeArgs(args: ERC20BridgeInitiated): Array[Byte] =
      HexBytesConverter.toBytes(
        TypeEncoder.encode(new LocalTokenType(args.localToken.hex)) +
          TypeEncoder.encode(new ClToType(args.clTo.publicKeyHash)) +
          TypeEncoder.encode(new ClAmountType(args.clAmount))
      )

    override def decodeLog(ethEventData: String): Either[String, ERC20BridgeInitiated] =
      try {
        FunctionReturnDecoder.decode(ethEventData, EventDef.getNonIndexedParameters).asScala.toList match {
          case (localToken: LocalTokenType) :: (clTo: ClToType) :: (clAmount: ClAmountType) :: Nil =>
            for {
              localToken <- EthAddress.from(localToken.getValue)
              clTo       <- Try(Address(clTo.getValue)).toEither.left.map(e => s"Can't decode clTo: ${e.getMessage}")
              clAmount   <- Try(clAmount.getValue.longValueExact()).toEither.left.map(e => s"Can't decode clAmount: ${e.getMessage}")
              _          <- Either.cond(clAmount > 0, (), s"clAmount must be positive, got: $clAmount")
            } yield new ERC20BridgeInitiated(localToken, clTo, clAmount)
          case xs =>
            Left(
              s"Expected (localToken: ${classOf[LocalTokenType].getSimpleName}, clTo: ${classOf[ClToType].getSimpleName}, " +
                s"clAmount: ${classOf[ClAmountType].getSimpleName}) fields, got: ${xs.mkString(", ")}"
            )
        }
      } catch {
        case NonFatal(e) => Left(s"Can't decode event: ${e.getMessage}, data=$ethEventData")
      }
  }

  case class ERC20BridgeFinalized(localToken: EthAddress, elTo: EthAddress, clAmount: Long)
  object ERC20BridgeFinalized {
    type LocalTokenType = Web3JAddress
    type ElToType       = Web3JAddress
    type ClAmountType   = Int64

    private val EventDef: Event = new Event(
      "ERC20BridgeFinalized",
      List[TypeReference[?]](
        new TypeReference[LocalTokenType](false) {},
        new TypeReference[ElToType](false)       {},
        new TypeReference[ClAmountType](false)   {}
      ).asJava
    )

    val Topic = EventEncoder.encode(EventDef)

    def encodeArgs(args: ERC20BridgeFinalized): String =
      TypeEncoder.encode(new LocalTokenType(args.localToken.hex)) +
        TypeEncoder.encode(new ElToType(args.elTo.hex)) +
        TypeEncoder.encode(new ClAmountType(args.clAmount))

    def decodeLog(ethEventData: String): Either[String, ERC20BridgeFinalized] =
      try {
        FunctionReturnDecoder.decode(ethEventData, EventDef.getNonIndexedParameters).asScala.toList match {
          case (assetAddress: LocalTokenType) :: (elTo: ElToType) :: (clAmount: ClAmountType) :: Nil =>
            for {
              localToken <- EthAddress.from(assetAddress.getValue)
              elTo       <- EthAddress.from(elTo.getValue)
              clAmount   <- Try(clAmount.getValue.longValueExact()).toEither.left.map(e => s"Can't decode clAmount: ${e.getMessage}")
              _          <- Either.cond(clAmount > 0, clAmount, s"clAmount must be positive, got: $clAmount")
            } yield new ERC20BridgeFinalized(localToken, elTo, clAmount)
          case xs =>
            Left(
              s"Expected (localToken: ${classOf[LocalTokenType].getSimpleName}, elTo: ${classOf[ElToType].getSimpleName}, " +
                s"clAmount: ${classOf[ClAmountType].getSimpleName}) fields, got: ${xs.mkString(", ")}"
            )
        }
      } catch {
        case NonFatal(e) => Left(s"Can't decode event: ${e.getMessage}, data=$ethEventData")
      }
  }

  case class RegistryUpdated(addedTokens: List[EthAddress], addedTokenExponents: List[Int], removedTokens: List[EthAddress])
  object RegistryUpdated {
    type AddedTokensComponentType = Web3JAddress
    type AddedTokensType          = Web3JArray[AddedTokensComponentType]

    type AddedTokenExponentsComponentType = Uint8
    type AddedTokenExponentsType          = Web3JArray[AddedTokenExponentsComponentType]

    type RemovedTokensComponentType = Web3JAddress
    type RemovedTokensType          = Web3JArray[RemovedTokensComponentType]

    private val EventDef: Event = new Event(
      "RegistryUpdated",
      List[TypeReference[?]](
        new TypeReference[AddedTokensType](false)         {},
        new TypeReference[AddedTokenExponentsType](false) {},
        new TypeReference[RemovedTokensType](false)       {}
      ).asJava
    )

    val Topic = EventEncoder.encode(EventDef)

    def encodeArgs(args: RegistryUpdated): String =
      TypeEncoder.encode(
        new AddedTokensType(
          classOf[AddedTokensComponentType],
          args.addedTokens.map(x => new AddedTokensComponentType(x.hexNoPrefix)).asJava
        )
      ) +
        TypeEncoder.encode(
          new AddedTokenExponentsType(
            classOf[AddedTokenExponentsComponentType],
            args.addedTokenExponents.map(new AddedTokenExponentsComponentType(_)).asJava
          )
        ) +
        TypeEncoder.encode(
          new RemovedTokensType(
            classOf[RemovedTokensComponentType],
            args.removedTokens.map(x => new RemovedTokensComponentType(x.hexNoPrefix)).asJava
          )
        )

    def decodeLog(ethEventData: String): Either[String, RegistryUpdated] =
      try {
        FunctionReturnDecoder.decode(ethEventData, EventDef.getNonIndexedParameters).asScala.toList match {
          case (addedTokens: AddedTokensType @unchecked) ::
              (addedTokenExponents: AddedTokenExponentsType @unchecked) ::
              (removedTokens: RemovedTokensType @unchecked) :: Nil
              if addedTokens.getComponentType == classOf[AddedTokensComponentType] &&
                addedTokenExponents.getComponentType == classOf[AddedTokenExponentsComponentType] &&
                removedTokens.getComponentType == classOf[RemovedTokensComponentType] =>
            for {
              addedTokens <- Try(addedTokens.getValue.asScala.map(x => EthAddress.unsafeFrom(x.getValue)).toList).toEither.left.map(e =>
                s"Can't decode addedTokens: ${e.getMessage}"
              )
              addedTokenExponents <- Try(addedTokenExponents.getValue.asScala.map(_.getValue.intValueExact()).toList).toEither.left.map(e =>
                s"Can't decode addedTokenExponents: ${e.getMessage}"
              )
              removed <- Try(removedTokens.getValue.asScala.map(x => EthAddress.unsafeFrom(x.getValue)).toList).toEither.left.map(e =>
                s"Can't decode removedTokens: ${e.getMessage}"
              )
            } yield new RegistryUpdated(addedTokens, addedTokenExponents, removed)
          case xs =>
            Left(
              s"Expected (addedTokens: ${classOf[AddedTokensType].getSimpleName}, addedTokenExponents: ${classOf[AddedTokenExponentsType].getSimpleName}, " +
                s"removedTokens: ${classOf[RemovedTokensType].getSimpleName}) fields, got: ${xs.mkString(", ")}"
            )
        }
      } catch {
        case NonFatal(e) => Left(s"Can't decode event: ${e.getMessage}, data=$ethEventData")
      }
  }

  // See https://specs.optimism.io/protocol/deposits.html#execution
  def mkFinalizeBridgeErc20Transaction(
      transferIndex: Long,
      standardBridgeAddress: EthAddress,
      token: EthAddress,
      elTo: EthAddress,
      clAmount: Long
  ): DepositedTransaction = DepositedTransaction.create(
    sourceHash = DepositedTransaction.mkUserDepositedSourceHash(transferIndex),
    from = EthereumConstants.ZeroAddress.hexNoPrefix,
    to = standardBridgeAddress.hex,
    mint = BigInteger.ZERO,
    value = BigInteger.ZERO,
    gas = FinalizeBridgeErc20Gas,
    isSystemTx = true, // Gas won't be consumed
    data = HexBytesConverter.toBytes(finalizeBridgeErc20Call(token, elTo, clAmount))
  )

  def finalizeBridgeErc20Call(token: EthAddress, elTo: EthAddress, clAmount: Long): String = {
    val function = new Function(
      FinalizeBridgeErc20Function,
      util.Arrays.asList[Type[?]](
        new Web3JAddress(token.hexNoPrefix),
        new Web3JAddress(elTo.hexNoPrefix),
        new Int64(clAmount)
      ),
      Collections.emptyList
    )
    FunctionEncoder.encode(function)
  }

  def mkUpdateAssetRegistryTransaction(
      standardBridgeAddress: EthAddress,
      addedTokenExponents: List[Int],
      addedTokens: List[EthAddress]
  ): DepositedTransaction =
    DepositedTransaction.create(
      sourceHash = DepositedTransaction.mkUserDepositedSourceHash(HexBytesConverter.toBytes(addedTokens.last.hex)), // TODO
      from = EthereumConstants.ZeroAddress.hexNoPrefix,
      to = standardBridgeAddress.hex,
      mint = BigInteger.ZERO,
      value = BigInteger.ZERO,
      gas = UpdateAssetRegistryGas,
      isSystemTx = true, // Gas won't be consumed
      data = HexBytesConverter.toBytes(updateAssetRegistryCall(addedTokens, addedTokenExponents))
    )

  def updateAssetRegistryCall(addedTokens: List[EthAddress], addedTokenExponents: List[Int]): String = {
    val function = new Function(
      UpdateAssetRegistryFunction,
      util.Arrays.asList[Type[?]](
        new Web3JArray(classOf[Web3JAddress], addedTokens.map(x => new Web3JAddress(x.hexNoPrefix))*),
        new Web3JArray(classOf[Uint8], addedTokenExponents.map(new Uint8(_))*)
      ),
      Collections.emptyList
    )
    FunctionEncoder.encode(function)
  }
}
