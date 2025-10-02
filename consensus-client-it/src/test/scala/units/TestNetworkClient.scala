package units

import com.wavesplatform.network.{Handshake, LegacyFrameCodecL1, PeerDatabase, RawBytes}
import com.wavesplatform.network.message.Message
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.{ByteBuf, ByteBufUtil, Unpooled}
import io.netty.channel.embedded.EmbeddedChannel
import com.wavesplatform.account.Address
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelInitializer, MultiThreadIoEventLoopGroup}
import io.netty.handler.codec.LengthFieldPrepender

import scala.concurrent.duration.*
import units.network.BlockSpec

object TestNetworkClient {
  private val applicationVersion = (1, 5, 7)
  private val nodeName           = "test-client"

  def send(address: String, port: Int, chainContractAddress: Address, block: NetworkL2Block): Unit = {
    val applicationName: String = "wavesl2-" + chainContractAddress.toString.substring(0, 8)
    val handshake               = new Handshake(applicationName, applicationVersion, nodeName, 0L, None)

    val blockBytes = BlockSpec.serializeData(block)
    val group      = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
    try {
      val handshakeBytes = TestNetworkClient.handshakeBytes(handshake)
      val bootstrap = new Bootstrap()
        .group(group)
        .channel(classOf[NioSocketChannel])
        .handler(new ChannelInitializer[SocketChannel] {
          override def initChannel(ch: SocketChannel): Unit = {
            ch.pipeline().addLast(new ClientHandler(handshakeBytes, blockBytes))
          }
        })
      val future = bootstrap.connect(address, port).sync()
      future.channel().closeFuture().sync()
    } finally {
      group.shutdownGracefully()
    }
  }

  private final class ClientHandler(
      handshake: Array[Byte],
      block: Array[Byte]
  ) extends ChannelInboundHandlerAdapter {
    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      try {
        val handshakeMessage = Unpooled.wrappedBuffer(handshake)
        ctx.writeAndFlush(handshakeMessage)
        Thread.sleep(10)
        val blockMessage = Unpooled.wrappedBuffer(createBlockMessage(block))
        ctx.writeAndFlush(blockMessage)
      } finally {
        ctx.close()
      }
    }
  }

  private def handshakeBytes(handshake: Handshake): Array[Byte] = {
    val buffer = Unpooled.buffer()
    try {
      handshake.encode(buffer)
      ByteBufUtil.getBytes(buffer)
    } finally {
      buffer.release()
    }
  }

  private def createBlockMessage(blockBytes: Array[Byte]): Array[Byte] = {
    val channel = new EmbeddedChannel(
      new LengthFieldPrepender(Message.LengthFieldLength),
      new LegacyFrameCodecL1(PeerDatabase.NoOp, 3.minutes)
    )

    try {
      val rawBlockMessage = RawBytes(BlockSpec.messageCode, blockBytes)
      if (!channel.writeOutbound(rawBlockMessage))
        throw new IllegalStateException("Outbound pipeline rejected block message")

      channel.flushOutbound()

      val builder = Array.newBuilder[Byte]
      var buf     = channel.readOutbound[ByteBuf]()
      while (buf != null) {
        try builder ++= ByteBufUtil.getBytes(buf)
        finally buf.release()
        buf = channel.readOutbound[ByteBuf]()
      }
      builder.result()
    } finally {
      channel.finishAndReleaseAll()
    }
  }
}
