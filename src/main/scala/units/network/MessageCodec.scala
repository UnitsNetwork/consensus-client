package units.network

import com.wavesplatform.network.PeerDatabase
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec

import java.util
import scala.util.{Failure, Success}

@Sharable
class MessageCodec(peerDatabase: PeerDatabase) extends MessageToMessageCodec[RawBytes, Message] with ScorexLogging {

  import BasicMessagesRepo.specsByCodes

  override def encode(ctx: ChannelHandlerContext, msg: Message, out: util.List[AnyRef]): Unit = {
    val encodedMsg = msg match {
      // Have no spec
      case r: RawBytes => r
      // With a spec
      case GetPeers      => RawBytes.from(GetPeersSpec, GetPeers)
      case k: KnownPeers => RawBytes.from(PeersSpec, k)
      case g: GetPayload => RawBytes.from(GetPayloadSpec, g)

      case _ =>
        throw new IllegalArgumentException(s"Can't send message $msg to $ctx (unsupported)")
    }

    out.add(encodedMsg)
  }

  override def decode(ctx: ChannelHandlerContext, msg: RawBytes, out: util.List[AnyRef]): Unit = {
    specsByCodes(msg.code).deserializeData(msg.data) match {
      case Success(x) => out.add(x)
      case Failure(e) => block(ctx, e)
    }
  }

  protected def block(ctx: ChannelHandlerContext, e: Throwable): Unit = {
    peerDatabase.blacklistAndClose(ctx.channel(), s"Invalid message. ${e.getMessage}")
  }
}
