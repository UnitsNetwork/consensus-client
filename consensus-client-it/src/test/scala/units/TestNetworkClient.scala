package units

import com.wavesplatform.network.message.Message
import com.wavesplatform.network.{Handshake, LegacyFrameCodecL1, PeerDatabase, RawBytes}
import io.netty.bootstrap.Bootstrap
import com.wavesplatform.account.Address
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.*
import io.netty.handler.codec.LengthFieldPrepender
import units.network.BlockSpec

import scala.concurrent.duration.*

object TestNetworkClient {
  private val applicationVersion = (1, 5, 7)
  private val nodeName           = "test-client"

  def send(address: String, port: Int, chainContractAddress: Address, block: NetworkL2Block): Unit = {
    val applicationName: String = "wavesl2-" + chainContractAddress.toString.substring(0, 8)
    val handshake               = new Handshake(applicationName, applicationVersion, nodeName, 0L, None)
    val blockMessage            = RawBytes(BlockSpec.messageCode, BlockSpec.serializeData(block))

    val workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
    try {
      val bootstrap = new Bootstrap()
        .group(workerGroup)
        .channel(classOf[NioSocketChannel])
        .handler(new ChannelInitializer[SocketChannel] {
          override def initChannel(ch: SocketChannel): Unit = {
            ch.pipeline()
              .addLast(new ChannelInboundHandlerAdapter {
                override def channelActive(ctx: ChannelHandlerContext): Unit = {
                  val handshakeBuffer = handshake.encode(ctx.alloc().ioBuffer())
                  ctx.writeAndFlush(handshakeBuffer).addListener { (_: io.netty.channel.ChannelFuture) =>
                    Thread.sleep(100) // A small delay to process the handshake
                    ctx.channel().writeAndFlush(blockMessage).addListener(ChannelFutureListener.CLOSE)
                  }
                }
              })
              .addLast(new LengthFieldPrepender(Message.LengthFieldLength))
              .addLast(new LegacyFrameCodecL1(PeerDatabase.NoOp, 3.minutes))
          }
        })

      val channel = bootstrap.connect(address, port).sync().channel()
      channel.closeFuture().sync()
    } finally {
      workerGroup.shutdownGracefully().syncUninterruptibly()
    }
  }
}
