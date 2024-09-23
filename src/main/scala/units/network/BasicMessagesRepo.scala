package units.network

import cats.syntax.either.*
import com.wavesplatform.crypto.SignatureLength
import units.util.HexBytesConverter.*
import units.BlockHash
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
  override val messageCode: Message.MessageCode = 2: Byte

  override protected def unwrap(v: KnownPeers): Seq[InetSocketAddress] = v.peers

  override protected def wrap(addresses: Seq[InetSocketAddress]): KnownPeers = KnownPeers(addresses)
}

object GetPayloadSpec extends MessageSpec[GetPayload] {
  override val messageCode: MessageCode = 3: Byte

  override val maxLength: Int = SignatureLength

  override def serializeData(msg: GetPayload): Array[Byte] =
    toBytes(msg.hash)

  override def deserializeData(bytes: Array[Byte]): Try[GetPayload] =
    Try(GetPayload(BlockHash(bytes)))
}

object PayloadSpec extends MessageSpec[PayloadMessage] {
  override val messageCode: MessageCode = 4: Byte

  override val maxLength: Int = NetworkServer.MaxFrameLength

  override def serializeData(payloadMsg: PayloadMessage): Array[Byte] =
    payloadMsg.toBytes

  override def deserializeData(bytes: Array[Byte]): Try[PayloadMessage] =
    PayloadMessage.fromBytes(bytes).leftMap(err => new IllegalArgumentException(err)).toTry
}

object BasicMessagesRepo {
  type Spec = MessageSpec[? <: AnyRef]

  val specs: Seq[Spec] = Seq(
    GetPeersSpec,
    PeersSpec,
    GetPayloadSpec,
    PayloadSpec
  )

  val specsByCodes: Map[Byte, Spec]       = specs.map(s => s.messageCode -> s).toMap
  val specsByClasses: Map[Class[?], Spec] = specs.map(s => s.contentClass -> s).toMap
}
