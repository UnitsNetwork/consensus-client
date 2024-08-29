package units.network

import cats.syntax.either.*
import units.client.engine.EngineApiClient
import units.client.http.EcApiClient
import units.util.BlockToPayloadMapper
import units.{BlockHash, ClientError, NetworkL2Block}
import com.wavesplatform.network.id
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import monix.execution.Scheduler

import scala.concurrent.Future
import scala.util.{Failure, Success}

@Sharable
class HistoryReplier(httpApiClient: EcApiClient, engineApiClient: EngineApiClient)(implicit sc: Scheduler)
    extends ChannelInboundHandlerAdapter
    with ScorexLogging {

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
        loadBlockL2(hash)
          .map {
            case Right(blockL2) =>
              RawBytes(BlockSpec.messageCode, BlockSpec.serializeData(blockL2))
            case Left(err) => throw new NoSuchElementException(s"Error loading block $hash: $err")
          }
      )
    case _ => super.channelRead(ctx, msg)
  }

  private def loadBlockL2(hash: BlockHash): Future[Either[ClientError, NetworkL2Block]] = Future {
    for {
      blockJsonOpt       <- httpApiClient.getBlockByHashJson(hash)
      blockJson          <- Either.fromOption(blockJsonOpt, ClientError("block not found"))
      payloadBodyJsonOpt <- engineApiClient.getPayloadBodyByHash(hash)
      payloadBodyJson    <- Either.fromOption(payloadBodyJsonOpt, ClientError("payload body not found"))
      payload = BlockToPayloadMapper.toPayloadJson(blockJson, payloadBodyJson)
      blockL2 <- NetworkL2Block(payload)
    } yield blockL2
  }
}
