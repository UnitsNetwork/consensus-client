package units.network

import com.wavesplatform.utils.Schedulers
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{Channel, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import monix.execution.schedulers.SchedulerService
import monix.reactive.subjects.{ConcurrentSubject, Subject}

@Sharable
class MessageObserver extends ChannelInboundHandlerAdapter {

  private implicit val scheduler: SchedulerService = Schedulers.fixedPool(2, "message-observer-l2")

  val payloads: Subject[(Channel, PayloadMessage), (Channel, PayloadMessage)] = ConcurrentSubject.publish[(Channel, PayloadMessage)]

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case b: PayloadMessage => payloads.onNext((ctx.channel(), b))
    case _                 => super.channelRead(ctx, msg)
  }

  def shutdown(): Unit = {
    payloads.onComplete()
  }
}
