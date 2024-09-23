package units.network

import com.wavesplatform.utils.{Schedulers, ScorexLogging}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{Channel, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import monix.execution.schedulers.SchedulerService
import monix.reactive.subjects.{ConcurrentSubject, Subject}
import units.ExecutionPayloadInfo

@Sharable
class MessageObserver extends ChannelInboundHandlerAdapter with ScorexLogging {

  private implicit val scheduler: SchedulerService = Schedulers.fixedPool(2, "message-observer-l2")

  val payloads: Subject[(Channel, ExecutionPayloadInfo), (Channel, ExecutionPayloadInfo)] = ConcurrentSubject.publish[(Channel, ExecutionPayloadInfo)]

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case pm: PayloadMessage =>
      pm.payloadInfo match {
        case Right(epi) => payloads.onNext((ctx.channel(), epi))
        case Left(err)  => log.warn(err)
      }
    case _ => super.channelRead(ctx, msg)
  }

  def shutdown(): Unit = {
    payloads.onComplete()
  }
}
