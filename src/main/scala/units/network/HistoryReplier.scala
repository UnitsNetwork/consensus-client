package units.network

import cats.syntax.either.*
import com.wavesplatform.network.id
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import monix.execution.Scheduler
import units.client.engine.EngineApiClient
import units.util.BlockToPayloadMapper
import units.{BlockHash, ClientError, NetworkL2Block}

import scala.concurrent.Future
import scala.util.{Failure, Success}

@Sharable
class HistoryReplier(engineApiClient: EngineApiClient)(implicit sc: Scheduler) extends ChannelInboundHandlerAdapter with ScorexLogging {
  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case GetBlock(hash) =>
      loadBlockL2(hash).onComplete {
        case Failure(e) => log.debug(s"${id(ctx)} Error retrieving block $hash", e)
        case Success(Right(bs)) =>
          if (ctx.channel().isOpen) {
            ctx.writeAndFlush(bs)
          } else {
            log.trace(s"${id(ctx)} Channel is closed")
          }
        case Success(Left(ClientError(msg))) =>
          log.debug(s"Could not load block $hash: $msg")

      }
    case _ => super.channelRead(ctx, msg)
  }

  private def loadBlockL2(hash: BlockHash): Future[Either[ClientError, RawBytes]] = Future {
    for {
      blockJsonOpt       <- engineApiClient.getBlockByHashJson(hash)
      blockJson          <- Either.fromOption(blockJsonOpt, ClientError("block not found"))
      payloadBodyJsonOpt <- engineApiClient.getPayloadBodyByHash(hash)
      payloadBodyJson    <- Either.fromOption(payloadBodyJsonOpt, ClientError("payload body not found"))
      payload = BlockToPayloadMapper.toPayloadJson(blockJson, payloadBodyJson)
      blockL2 <- NetworkL2Block(payload)
    } yield RawBytes(BlockSpec.messageCode, BlockSpec.serializeData(blockL2))
  }
}
