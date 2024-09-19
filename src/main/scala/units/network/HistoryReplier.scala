package units.network

import com.wavesplatform.network.id
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import monix.execution.Scheduler
import units.client.engine.EngineApiClient
import units.{BlockHash, ClientError, NetworkBlock}

import scala.concurrent.Future
import scala.util.{Failure, Success}

@Sharable
class HistoryReplier(engineApiClient: EngineApiClient)(implicit sc: Scheduler) extends ChannelInboundHandlerAdapter with ScorexLogging {
  private def respondWith(ctx: ChannelHandlerContext, value: Future[Message]): Unit =
    value.onComplete {
      case Failure(e) => log.debug(s"${id(ctx)} Error processing request", e)
      case Success(value) =>
        if (ctx.channel().isOpen) {
          ctx.writeAndFlush(value)
        } else {
          log.trace(s"${id(ctx)} Channel is closed")
        }
    }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case GetBlock(hash) =>
      respondWith(
        ctx,
        loadBlock(hash)
          .map {
            case Right(block) =>
              RawBytes(BlockSpec.messageCode, BlockSpec.serializeData(block))
            case Left(err) => throw new NoSuchElementException(s"Error loading block $hash: $err")
          }
      )
    case _ => super.channelRead(ctx, msg)
  }

  private def loadBlock(hash: BlockHash): Future[Either[ClientError, NetworkBlock]] = Future {
    engineApiClient.getPayloadJsonDataByHash(hash).flatMap { payloadJsonData =>
      NetworkBlock(payloadJsonData.toPayloadJson)
    }
  }
}
