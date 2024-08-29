package units.network

import com.wavesplatform.network.{Handshake, HandshakeSpec, TrafficLogger as TL}
import io.netty.channel.ChannelHandler.Sharable
import units.NetworkL2Block
import units.network.BasicMessagesRepo.specsByCodes

@Sharable
class TrafficLogger(settings: TL.Settings) extends TL(settings) {

  import BasicMessagesRepo.specsByClasses

  protected def codeOf(msg: AnyRef): Option[Byte] = {
    val aux: PartialFunction[AnyRef, Byte] = {
      case x: RawBytes => x.code
      case _: NetworkL2Block => BlockSpec.messageCode
      case x: Message => specsByClasses(x.getClass).messageCode
      case _: Handshake => HandshakeSpec.messageCode
    }

    aux.lift(msg)
  }

  protected def stringify(msg: Any): String = msg match {
    case b: NetworkL2Block => s"${b.hash}"
    case RawBytes(code, data) => s"RawBytes(${specsByCodes(code).messageName}, ${data.length} bytes)"
    case other => other.toString
  }
}
