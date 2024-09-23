package units.network

import com.wavesplatform.network.message.MessageSpec
import units.BlockHash

import java.net.InetSocketAddress
import java.util

sealed trait Message

case object GetPeers extends Message

case class KnownPeers(peers: Seq[InetSocketAddress]) extends Message

case class GetPayload(hash: BlockHash) extends Message

case class RawBytes(code: Byte, data: Array[Byte]) extends Message {
  override def toString: String = s"RawBytes($code, ${data.length} bytes)"

  override def equals(obj: Any): Boolean = obj match {
    case o: RawBytes => o.code == code && util.Arrays.equals(o.data, data)
    case _ => false
  }
}

object RawBytes {
  def from[T <: AnyRef](spec: MessageSpec[T], message: T): RawBytes = RawBytes(spec.messageCode, spec.serializeData(message))
}
