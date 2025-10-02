package units

import com.wavesplatform.lang.Global
import com.wavesplatform.network.Handshake
import io.netty.buffer.{ByteBufUtil, Unpooled}

import java.io.BufferedOutputStream
import java.net.{InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.util.Arrays

final class TestNetworkClient(address: String, port: Int, handshake: Handshake, blockBytes: Array[Byte]) {
  def send(): Unit = {
    val socket = new Socket()
    try {
      socket.connect(new InetSocketAddress(address, port))
      val out = new BufferedOutputStream(socket.getOutputStream)

      val handshakeMessage = TestNetworkClient.handshakeBytes(handshake)
      out.write(handshakeMessage)
      out.flush()

      Thread.sleep(10)

      val blockMessage = TestNetworkClient.createBlockMessage(blockBytes)
      out.write(blockMessage)
      out.flush()
    } finally {
      socket.close()
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
    // 4 bytes of magic code
    val magicBytes = ByteBuffer.allocate(4).putInt(MAGIC_CODE).array()

    // Checksum (first 4 bytes of Blake2b)
    val checksum = calculateChecksum(blockBytes)

    // Block length (4 bytes)
    val lengthBytes = ByteBuffer.allocate(4).putInt(blockBytes.length).array()

    // Block message body: magic(4) + type(1) + length(4) + checksum(4) + data(N)
    val blockMessageBuffer =
      ByteBuffer.allocate(4 + 1 + 4 + CHECKSUM_LENGTH + blockBytes.length)
    blockMessageBuffer.put(magicBytes)
    blockMessageBuffer.put(BLOCK_MESSAGE_TYPE)
    blockMessageBuffer.put(lengthBytes)
    blockMessageBuffer.put(checksum)
    blockMessageBuffer.put(blockBytes)

    val blockMessageBytes = blockMessageBuffer.array()

    // Total length prefix + block message
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
