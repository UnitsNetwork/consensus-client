package units.el

import cats.syntax.traverse.*
import com.wavesplatform.account.{Address, AddressScheme}
import com.wavesplatform.common.merkle.{Digest, Merkle}
import com.wavesplatform.utils.EthEncoding
import org.web3j.abi.datatypes.Event
import org.web3j.abi.datatypes.generated.{Bytes20, Int64}
import org.web3j.abi.{FunctionReturnDecoder, TypeEncoder, TypeReference}
import units.client.engine.model.GetLogsResponseEntry
import units.eth.Gwei

import java.math.BigInteger
import scala.jdk.CollectionConverters.{ListHasAsScala, SeqHasAsJava}
import scala.util.Try
import scala.util.control.NonFatal

object Bridge {
  private val ExactTransfersNumber = 1024
  private val PublicKeyHashType    = new TypeReference[Bytes20](false) {}
  private val AmountType           = new TypeReference[Int64](false) {}

  private val ElSentNativeEventDef: Event = new Event(
    "SentNative",
    List[TypeReference[?]](PublicKeyHashType, AmountType).asJava
  )

  val ElSentNativeEventTopic = org.web3j.abi.EventEncoder.encode(ElSentNativeEventDef)

  /** @param amount
    *   In waves units, see bridge.sol
    */
  case class ElSentNativeEvent(wavesAddress: Address, amount: Long)

  // TODO BridgeMerkleTree
  object ElSentNativeEvent {
    def encodeArgs(args: ElSentNativeEvent): String = {
      val wavesAddress = new Bytes20(args.wavesAddress.publicKeyHash)
      require(wavesAddress.getClass == PublicKeyHashType.getClassType) // Make sure types are correct

      val amount = new Int64(args.amount)
      require(amount.getClass == AmountType.getClassType)

      TypeEncoder.encode(wavesAddress) + TypeEncoder.encode(amount)
    }

    def decodeLog(ethEventData: String, chainId: Byte = AddressScheme.current.chainId): Either[String, ElSentNativeEvent] =
      try {
        FunctionReturnDecoder.decode(ethEventData, ElSentNativeEventDef.getNonIndexedParameters).asScala.toList match {
          case (publicKeyHash: Bytes20) :: (rawTransferAmount: Int64) :: Nil =>
            for {
              wavesRecipient <- Try(Address(publicKeyHash.getValue, chainId)).toEither.left.map(e => s"Can't decode address: ${e.getMessage}")
              transferAmount <- Try(rawTransferAmount.getValue.longValueExact()).toEither.left.map(e => s"Can't decode amount: ${e.getMessage}")
              _              <- Either.cond(transferAmount > 0, transferAmount, s"Transfer amount must be positive, got: $transferAmount")
            } yield new ElSentNativeEvent(wavesRecipient, transferAmount)
          case xs =>
            Left(
              s"Expected (wavesRecipient: ${PublicKeyHashType.getClassType.getSimpleName}, amount: ${AmountType.getClassType.getSimpleName}) fields, got: ${xs
                .mkString(", ")}"
            )
        }
      } catch {
        case NonFatal(e) => Left(s"Can't decode event: ${e.getMessage}")
      }
  }

  def mkTransfersHash(elRawLogs: Seq[GetLogsResponseEntry]): Either[String, Digest] = {
    for {
      bridgeEvents <- elRawLogs.traverse { l =>
        ElSentNativeEvent.decodeLog(l.data).left.map { e =>
          s"Log decoding error in ${l.data}: $e. Try to upgrade your consensus client"
        }
      }
    } yield {
      if (bridgeEvents.isEmpty) Array.emptyByteArray
      else {
        val data     = elRawLogs.map(l => EthEncoding.toBytes(l.data))
        val levels   = Merkle.mkLevels(padData(data, ExactTransfersNumber))
        val rootHash = levels.head.head
        rootHash
      }
    }
  }

  def mkTransferProofs(events: Seq[ElSentNativeEvent], transferIndex: Int): Seq[Digest] =
    if (events.isEmpty) Nil
    else {
      val data   = events.map(x => EthEncoding.toBytes(ElSentNativeEvent.encodeArgs(x)))
      val levels = Merkle.mkLevels(padData(data, ExactTransfersNumber))
      Merkle.mkProofs(transferIndex, levels)
    }

  def clToGweiNativeTokenAmount(amountInWavesToken: Long): Gwei =
    Gwei.ofRawGwei(BigInteger.valueOf(amountInWavesToken).multiply(BigInteger.valueOf(10))) // 1 unit is 10 Gwei (see bridge.sol)
}
