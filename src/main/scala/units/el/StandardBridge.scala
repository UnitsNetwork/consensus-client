package units.el

import cats.syntax.either.*
import com.wavesplatform.account.Address
import com.wavesplatform.utils.EthEncoding
import org.web3j.abi.*
import org.web3j.abi.datatypes.generated.{Int64, Uint256, Uint8}
import org.web3j.abi.datatypes.{Event, Function, Type, Address as Web3JAddress, DynamicArray as Web3JArray}
import units.*
import units.client.engine.model.GetLogsResponseEntry
import units.eth.{EthAddress, EthereumConstants}
import units.util.HexBytesConverter

import java.math.BigInteger
import java.util
import java.util.Collections
import scala.jdk.CollectionConverters.{CollectionHasAsScala, SeqHasAsJava}
import scala.util.Try
import scala.util.control.NonFatal

object StandardBridge {
  val FinalizeBridgeETHFunction = "finalizeBridgeETH"
  val FinalizeBridgeETHGas      = BigInteger.valueOf(1_000_000L)

  val FinalizeBridgeErc20Function = "finalizeBridgeERC20"
  val FinalizeBridgeErc20Gas      = BigInteger.valueOf(1_000_000L) // Should be enough to run this function

  val UpdateAssetRegistryFunction = "updateAssetRegistry"
  val UpdateAssetRegistryGas      = BigInteger.valueOf(1_000_000L)

  case class ETHBridgeFinalized(from: EthAddress, to: EthAddress, amount: EAmount)

  object ETHBridgeFinalized {
    type FromType    = Web3JAddress
    type ElToType    = Web3JAddress
    type EAmountType = Uint256

    private val FromTypeRef   = new TypeReference[FromType](true) {}
    private val ElToTypeRef   = new TypeReference[ElToType](true) {}
    private val AmountTypeRef = new TypeReference[EAmountType](false) {}

    private val EventDef = new Event(
      "ETHBridgeFinalized",
      List[TypeReference[?]](
        FromTypeRef,
        ElToTypeRef,
        AmountTypeRef
      ).asJava
    )

    val Topic = EventEncoder.encode(EventDef)

    def decodeLog(log: GetLogsResponseEntry): Either[String, ETHBridgeFinalized] =
      (try {
        for {
          (from, elTo) <- log.topics match {
            case _ :: from :: elTo :: Nil => Right((from, elTo))
            case _                        => Left(s"Topics should contain 3 or more elements, got ${log.topics.size}")
          }
          from <- Try(FunctionReturnDecoder.decodeIndexedValue(from, FromTypeRef)).toEither.bimap(
            e => s"Can't decode from: ${e.getMessage}",
            r => r.asInstanceOf[FromType]
          )
          from <- EthAddress.from(from.getValue)
          elTo <- Try(FunctionReturnDecoder.decodeIndexedValue(elTo, ElToTypeRef)).toEither.bimap(
            e => s"Can't decode elTo: ${e.getMessage}",
            r => r.asInstanceOf[ElToType]
          )
          elTo <- EthAddress.from(elTo.getValue)
          amount <- FunctionReturnDecoder.decode(log.data, EventDef.getNonIndexedParameters).asScala.toList match {
            case (amount: EAmountType) :: Nil =>
              for {
                amount <- Try(amount.getValue).toEither.left.map(e => s"Can't decode amount: ${e.getMessage}")
                _      <- Either.raiseUnless(amount.compareTo(BigInteger.ZERO) > 0)(s"amount must be positive, got: $amount")
              } yield amount
            case xs => Left(s"Expected (amount: ${classOf[EAmountType].getSimpleName}) non-indexed fields, got: ${xs.mkString(", ")}")
          }
        } yield ETHBridgeFinalized(from, elTo, EAmount(amount))
      } catch {
        case NonFatal(e) => Left(e.getMessage)
      }).left.map(e => s"Can't decode ${EventDef.getName} event from $log. $e")
  }

  case class ERC20BridgeInitiated(localToken: EthAddress, clTo: Address, elFrom: EthAddress, clAmount: Long)
  object ERC20BridgeInitiated extends BridgeMerkleTree[ERC20BridgeInitiated] {
    type LocalTokenType = Web3JAddress
    type ClToType       = Web3JAddress
    type ClAmountType   = Int64

    private val LocalTokenTypeRef = new TypeReference[LocalTokenType](true) {}
    private val ClToTypeRef       = new TypeReference[ClToType](true) {}

    private val EventDef: Event = new Event(
      "ERC20BridgeInitiated",
      List[TypeReference[?]](
        LocalTokenTypeRef,
        ClToTypeRef,
        ClToTypeRef,
        new TypeReference[ClAmountType](false) {}
      ).asJava
    )

    val Topic = EventEncoder.encode(EventDef)

    override def encodeArgsForMerkleTree(args: ERC20BridgeInitiated): Array[Byte] =
      HexBytesConverter.toBytes(
        TypeEncoder.encode(new LocalTokenType(args.localToken.hex)) +
          TypeEncoder.encode(new ClToType(EthEncoding.toHexString(args.clTo.publicKeyHash))) +
          TypeEncoder.encode(new ClAmountType(args.clAmount))
      )

    override def decodeLog(log: GetLogsResponseEntry): Either[String, ERC20BridgeInitiated] =
      (try {
        for {
          (localToken, from, clTo) <- log.topics match {
            case _ :: localToken :: from :: clTo :: Nil => Right((localToken, from, clTo))
            case _                                      => Left(s"Topics should contain 4 or more elements, got ${log.topics.size}")
          }
          localToken <- Try(FunctionReturnDecoder.decodeIndexedValue(localToken, LocalTokenTypeRef)).toEither.bimap(
            e => s"Can't decode localToken: ${e.getMessage}",
            r => r.asInstanceOf[LocalTokenType] // Type is not inferred even if call decodeIndexedValue[LocalTokenType]
          )
          localToken <- EthAddress.from(localToken.getValue)
          from <- Try(FunctionReturnDecoder.decodeIndexedValue(from, ClToTypeRef)).toEither.bimap(
            e => s"Can't decode clTo: ${e.getMessage}",
            r => r.asInstanceOf[ClToType]
          )
          from <- EthAddress.from(from.getValue)
          clTo <- Try(FunctionReturnDecoder.decodeIndexedValue(clTo, ClToTypeRef)).toEither.bimap(
            e => s"Can't decode clTo: ${e.getMessage}",
            r => r.asInstanceOf[ClToType]
          )
          clTo <- Try(Address.fromHexString(clTo.getValue)).toEither.left.map(e => s"Can't decode clTo: ${e.getMessage}")
          clAmount <- FunctionReturnDecoder.decode(log.data, EventDef.getNonIndexedParameters).asScala.toList match {
            case (clAmount: ClAmountType) :: Nil =>
              for {
                clAmount <- Try(clAmount.getValue.longValueExact()).toEither.left.map(e => s"Can't decode clAmount: ${e.getMessage}")
                _        <- Either.cond(clAmount > 0, (), s"clAmount must be positive, got: $clAmount")
              } yield clAmount

            case xs => Left(s"Expected (clAmount: ${classOf[ClAmountType].getSimpleName}) non-indexed fields, got: ${xs.mkString(", ")}")
          }
        } yield new ERC20BridgeInitiated(localToken, clTo, from, clAmount)
      } catch {
        case NonFatal(e) => Left(e.getMessage)
      }).left.map(e => s"Can't decode ${EventDef.getName} event from $log. $e")
  }

  case class ERC20BridgeFinalized(localToken: EthAddress, from: EthAddress, elTo: EthAddress, amount: EAmount)
  object ERC20BridgeFinalized {
    type LocalTokenType = Web3JAddress
    type FromType       = Web3JAddress
    type ElToType       = Web3JAddress
    type EAmountType    = Uint256

    private val LocalTokenTypeRef = new TypeReference[LocalTokenType](true) {}
    private val FromTypeRef       = new TypeReference[FromType](true) {}
    private val ElToTypeRef       = new TypeReference[ElToType](true) {}

    private val EventDef: Event = new Event(
      "ERC20BridgeFinalized",
      List[TypeReference[?]](
        LocalTokenTypeRef,
        FromTypeRef,
        ElToTypeRef,
        new TypeReference[EAmountType](false) {}
      ).asJava
    )

    val Topic = EventEncoder.encode(EventDef)

    def decodeLog(log: GetLogsResponseEntry): Either[String, ERC20BridgeFinalized] =
      (try {
        for {
          (localToken, from, elTo) <- log.topics match {
            case _ :: localToken :: from :: elTo :: Nil => Right((localToken, from, elTo))
            case _                                      => Left(s"Topics should contain 4 or more elements, got ${log.topics.size}")
          }
          localToken <- Try(FunctionReturnDecoder.decodeIndexedValue(localToken, LocalTokenTypeRef)).toEither.bimap(
            e => s"Can't decode localToken: ${e.getMessage}",
            r => r.asInstanceOf[LocalTokenType]
          )
          localToken <- EthAddress.from(localToken.getValue)
          from <- Try(FunctionReturnDecoder.decodeIndexedValue(from, FromTypeRef)).toEither.bimap(
            e => s"Can't decode from: ${e.getMessage}",
            r => r.asInstanceOf[FromType]
          )
          from <- EthAddress.from(from.getValue)
          elTo <- Try(FunctionReturnDecoder.decodeIndexedValue(elTo, ElToTypeRef)).toEither.bimap(
            e => s"Can't decode elTo: ${e.getMessage}",
            r => r.asInstanceOf[ElToType]
          )
          elTo <- EthAddress.from(elTo.getValue)
          amount <- FunctionReturnDecoder.decode(log.data, EventDef.getNonIndexedParameters).asScala.toList match {
            case (amount: EAmountType) :: Nil =>
              for {
                amount <- Try(amount.getValue).toEither.left.map(e => s"Can't decode amount: ${e.getMessage}")
                _      <- Either.cond(amount.compareTo(BigInteger.ZERO) > 0, (), s"amount must be positive, got: $amount")
              } yield amount

            case xs => Left(s"Expected (amount: ${classOf[EAmountType].getSimpleName}) non-indexed fields, got: ${xs.mkString(", ")}")
          }
        } yield new ERC20BridgeFinalized(localToken, from, elTo, EAmount(amount))
      } catch {
        case NonFatal(e) => Left(e.getMessage)
      }).left.map(e => s"Can't decode event ${EventDef.getName} from $log. $e")
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

    def decodeLog(ethEventData: String): Either[String, RegistryUpdated] =
      (try {
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
        case NonFatal(e) => Left(e.getMessage)
      }).left.map(e => s"Can't decode event ${EventDef.getName} from $ethEventData. $e")
  }

  def mkFinalizeBridgeETHTransaction(
      transferIndex: Long,
      standardBridgeAddress: EthAddress,
      from: EthAddress,
      to: EthAddress,
      amount: Long
  ): DepositedTransaction = {
    val elAmount = WAmount(amount).scale(NativeTokenElDecimals - NativeTokenClDecimals)
    DepositedTransaction(
      sourceHash = DepositedTransaction.mkUserDepositedSourceHash(transferIndex),
      from = EthereumConstants.ZeroAddress,
      to = standardBridgeAddress,
      mint = elAmount.raw,
      value = elAmount.raw,
      gas = FinalizeBridgeETHGas,
      isSystemTx = true,
      data = finalizeBridgeETHCall(from, to, elAmount)
    )
  }

  private def finalizeBridgeETHCall(from: EthAddress, elTo: EthAddress, amount: EAmount): String = {
    val function = new Function(
      FinalizeBridgeETHFunction,
      util.Arrays.asList[Type[?]](
        new Web3JAddress(from.hexNoPrefix),
        new Web3JAddress(elTo.hexNoPrefix),
        new Uint256(amount.raw)
      ),
      Collections.emptyList
    )
    FunctionEncoder.encode(function)
  }

  // See https://specs.optimism.io/protocol/deposits.html#execution
  def mkFinalizeBridgeErc20Transaction(
      transferIndex: Long,
      standardBridgeAddress: EthAddress,
      token: EthAddress,
      from: EthAddress,
      to: EthAddress,
      amount: EAmount
  ): DepositedTransaction = DepositedTransaction(
    sourceHash = DepositedTransaction.mkUserDepositedSourceHash(transferIndex),
    from = EthereumConstants.ZeroAddress,
    to = standardBridgeAddress,
    mint = BigInteger.ZERO,
    value = BigInteger.ZERO,
    gas = FinalizeBridgeErc20Gas,
    isSystemTx = true, // Gas won't be consumed
    data = finalizeBridgeErc20Call(token, from, to, amount)
  )

  private def finalizeBridgeErc20Call(token: EthAddress, from: EthAddress, elTo: EthAddress, amount: EAmount): String = {
    val function = new Function(
      FinalizeBridgeErc20Function,
      util.Arrays.asList[Type[?]](
        new Web3JAddress(token.hexNoPrefix),
        new Web3JAddress(from.hexNoPrefix),
        new Web3JAddress(elTo.hexNoPrefix),
        new Uint256(amount.raw)
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
    DepositedTransaction(
      sourceHash = DepositedTransaction.mkUserDepositedSourceHash(HexBytesConverter.toBytes(addedTokens.last.hex)), // TODO
      from = EthereumConstants.ZeroAddress,
      to = standardBridgeAddress,
      mint = BigInteger.ZERO,
      value = BigInteger.ZERO,
      gas = UpdateAssetRegistryGas,
      isSystemTx = true, // Gas won't be consumed
      data = updateAssetRegistryCall(addedTokens, addedTokenExponents)
    )

  private def updateAssetRegistryCall(addedTokens: List[EthAddress], addedTokenExponents: List[Int]): String = {
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
