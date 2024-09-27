package units.network

import com.wavesplatform.utils.{Schedulers, ScorexLogging}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import monix.execution.schedulers.SchedulerService
import monix.reactive.subjects.{ConcurrentSubject, Subject}

@Sharable
class MessageObserver extends ChannelInboundHandlerAdapter with ScorexLogging {

  private implicit val scheduler: SchedulerService = Schedulers.fixedPool(2, "message-observer-l2")

  val payloads: Subject[PayloadMessageWithChannel, PayloadMessageWithChannel] = ConcurrentSubject.publish[PayloadMessageWithChannel]

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case pm: PayloadMessage => payloads.onNext(PayloadMessageWithChannel(pm, ctx.channel()))
    case _                  => super.channelRead(ctx, msg)
  }

  def shutdown(): Unit = {
    payloads.onComplete()
  }
}
