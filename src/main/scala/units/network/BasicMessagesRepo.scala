package units.network

import cats.syntax.either.*
import com.google.common.primitives.Bytes
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
import com.wavesplatform.crypto.SignatureLength
import units.util.HexBytesConverter.*
import units.{BlockHash, NetworkL2Block}
import com.wavesplatform.network.message.Message.MessageCode
import com.wavesplatform.network.message.{Message, MessageSpec}
import com.wavesplatform.network.{InetSocketAddressSeqSpec, NetworkServer}

import java.net.InetSocketAddress
import scala.util.Try

object GetPeersSpec extends MessageSpec[GetPeers.type] {
  override val messageCode: Message.MessageCode = 1: Byte

  override val maxLength: Int = 0

  override def deserializeData(bytes: Array[Byte]): Try[GetPeers.type] =
    Try {
      require(bytes.isEmpty, "Non-empty data for GetPeers")
      GetPeers
    }

  override def serializeData(data: GetPeers.type): Array[Byte] = Array()
}

object PeersSpec extends InetSocketAddressSeqSpec[KnownPeers] {
  override protected def unwrap(v: KnownPeers): Seq[InetSocketAddress] = v.peers

  override protected def wrap(addresses: Seq[InetSocketAddress]): KnownPeers = KnownPeers(addresses)
}

object GetBlockL2Spec extends MessageSpec[GetBlock] {
  override val messageCode: MessageCode = 3: Byte

  override val maxLength: Int = SignatureLength

  override def serializeData(msg: GetBlock): Array[Byte] = toBytes(msg.hash.str)

  override def deserializeData(bytes: Array[Byte]): Try[GetBlock] = Try {
    require(
      NetworkL2Block.validateReferenceLength(bytes.length),
      s"Invalid hash length ${bytes.length} in GetBlock message, expecting ${crypto.DigestLength}"
    )
    GetBlock(BlockHash(bytes))
  }
}

object BlockSpec extends MessageSpec[NetworkL2Block] {
  override val messageCode: MessageCode = 4: Byte

  override val maxLength: Int = NetworkServer.MaxFrameLength

  override def serializeData(block: NetworkL2Block): Array[Byte] = {
    val signatureBytes = block.signature.map(sig => Bytes.concat(Array(1.toByte), sig.arr)).getOrElse(Array(0.toByte))
    Bytes.concat(signatureBytes, block.payloadBytes)
  }

  override def deserializeData(bytes: Array[Byte]): Try[NetworkL2Block] = {
    // We need a signature only for blocks those are not confirmed on the chain contract
    val isWithSignature = bytes.headOption.contains(1.toByte)
    val signature       = if (isWithSignature) Some(ByteStr(bytes.slice(1, SignatureLength + 1))) else None
    val payloadOffset   = if (isWithSignature) SignatureLength + 1 else 1
    for {
      _     <- Either.cond(signature.forall(_.size == SignatureLength), (), new RuntimeException("Invalid block signature size")).toTry
      block <- NetworkL2Block(bytes.drop(payloadOffset), signature).leftMap(err => new RuntimeException(err)).toTry
    } yield block
  }
}

object BasicMessagesRepo {
  type Spec = MessageSpec[? <: AnyRef]

  val specs: Seq[Spec] = Seq(
    GetPeersSpec,
    PeersSpec,
    GetBlockL2Spec,
    BlockSpec
  )

  val specsByCodes: Map[Byte, Spec]       = specs.map(s => s.messageCode -> s).toMap
  val specsByClasses: Map[Class[?], Spec] = specs.map(s => s.contentClass -> s).toMap
}
