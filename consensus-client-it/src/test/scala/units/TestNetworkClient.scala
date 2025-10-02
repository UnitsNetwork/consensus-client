package units

import com.wavesplatform.lang.Global
import com.wavesplatform.network.Handshake
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.{ByteBufUtil, Unpooled}
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelInitializer, MultiThreadIoEventLoopGroup}

import java.nio.ByteBuffer
import java.util.Arrays

final class TestNetworkClient(
    address: String,
    port: Int,
    handshake: Handshake,
    blockBytes: Array[Byte]
) {
  def send(): Unit = {
    val group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
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
        val blockMessage = Unpooled.wrappedBuffer(TestNetworkClient.createBlockMessage(block))
        ctx.writeAndFlush(blockMessage)
      } finally {
        ctx.close()
      }
    }
  }
}

object TestNetworkClient {
  private val MAGIC_CODE: Int          = 0x12345678
  private val BLOCK_MESSAGE_TYPE: Byte = 4
  private val CHECKSUM_LENGTH: Int     = 4

  def handshakeBytes(handshake: Handshake): Array[Byte] = {
    val buffer = Unpooled.buffer()
    try {
      handshake.encode(buffer)
      ByteBufUtil.getBytes(buffer)
    } finally {
      buffer.release()
    }
  }

  def createBlockMessage(blockBytes: Array[Byte]): Array[Byte] = {
    val magicBytes  = ByteBuffer.allocate(4).putInt(MAGIC_CODE).array()
    val checksum    = calculateChecksum(blockBytes)
    val lengthBytes = ByteBuffer.allocate(4).putInt(blockBytes.length).array()

    val blockMessageBuffer =
      ByteBuffer.allocate(4 + 1 + 4 + CHECKSUM_LENGTH + blockBytes.length)
    blockMessageBuffer.put(magicBytes)
    blockMessageBuffer.put(BLOCK_MESSAGE_TYPE)
    blockMessageBuffer.put(lengthBytes)
    blockMessageBuffer.put(checksum)
    blockMessageBuffer.put(blockBytes)

    val blockMessageBytes = blockMessageBuffer.array()

    val totalLengthBytes =
      ByteBuffer.allocate(4).putInt(blockMessageBytes.length).array()

    val messageBuffer = ByteBuffer.allocate(4 + blockMessageBytes.length)
    messageBuffer.put(totalLengthBytes)
    messageBuffer.put(blockMessageBytes)
    messageBuffer.array()
  }

  private def calculateChecksum(data: Array[Byte]): Array[Byte] = {
    val hash = Global.blake2b256(data)
    Arrays.copyOfRange(hash, 0, CHECKSUM_LENGTH)
  }
}
