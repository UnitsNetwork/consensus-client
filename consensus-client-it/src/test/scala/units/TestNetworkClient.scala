package units

import com.wavesplatform.lang.Global
import com.wavesplatform.network.Handshake

import java.io.{BufferedOutputStream, ByteArrayOutputStream, DataOutputStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays

final class TestNetworkClient(address: String, port: Int, handshake: Handshake, blockBytes: Array[Byte]) {
  def send(): Unit = {
    val socket = new Socket()
    try {
      socket.connect(new InetSocketAddress(address, port))
      val out = new BufferedOutputStream(socket.getOutputStream)

      val handshakeMessage = TestNetworkClient.encodeHandshake(handshake)
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

  def encodeHandshake(handshake: Handshake): Array[Byte] = {
    val out  = new ByteArrayOutputStream()
    val data = new DataOutputStream(out)

    val appNameBytes = handshake.applicationName.getBytes(StandardCharsets.UTF_8)
    data.writeByte(appNameBytes.length)
    data.write(appNameBytes)

    data.writeInt(handshake.applicationVersion._1)
    data.writeInt(handshake.applicationVersion._2)
    data.writeInt(handshake.applicationVersion._3)

    val nodeNameBytes = handshake.nodeName.getBytes(StandardCharsets.UTF_8)
    data.writeByte(nodeNameBytes.length)
    data.write(nodeNameBytes)

    data.writeLong(handshake.nodeNonce)

    handshake.declaredAddress.flatMap(addr => Option(addr.getAddress).map(address => (address, addr.getPort))) match {
      case Some((address, port)) =>
        val addressBytes = address.getAddress
        data.writeInt(addressBytes.length + Integer.BYTES)
        data.write(addressBytes)
        data.writeInt(port)
      case None =>
        data.writeInt(0)
    }

    val timestampSeconds = System.currentTimeMillis() / 1000
    data.writeLong(timestampSeconds)

    data.flush()
    out.toByteArray
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
