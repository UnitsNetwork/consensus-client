package units

import com.wavesplatform.account.Address
import com.wavesplatform.network.PeerDatabase
import com.wavesplatform.network.client.NetworkClient
import units.network.{BlockSpec, LegacyFrameCodec, RawBytes}

import java.net.InetSocketAddress
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

object TestNetworkClient {
  def send(node: WavesNodeContainer, chainContractAddress: Address, block: NetworkL2Block): Unit = {
    val applicationName: String = "wavesl2-" + chainContractAddress.toString.substring(0, 8)
    val client                  = NetworkClient(applicationName, frameCodec = new LegacyFrameCodec(PeerDatabase.NoOp))
    val blockMessage            = RawBytes(BlockSpec.messageCode, BlockSpec.serializeData(block))

    val f = client
      .connect(new InetSocketAddress(address, port))
      .map { _.writeAndFlush(blockMessage) }
      .andThen { case _ =>
        client.shutdown()
      }

    Await.result(f, 20.seconds)

  }
}
