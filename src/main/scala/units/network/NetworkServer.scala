package units.network

import units.ClientConfig
import com.wavesplatform.network.{NetworkServer as NS, PeerDatabase, PeerInfo}
import com.wavesplatform.settings.Constants
import io.netty.channel.group.ChannelGroup
import io.netty.channel.{Channel, ChannelHandlerAdapter}

import java.util.concurrent.ConcurrentHashMap

object NetworkServer {

  def apply(
             settings: ClientConfig,
             historyReplier: HistoryReplier,
             peerDatabase: PeerDatabase,
             messageObserver: MessageObserver,
             allChannels: ChannelGroup,
             peerInfo: ConcurrentHashMap[Channel, PeerInfo]
  ): NS = {
    val applicationName = s"${Constants.ApplicationName}l2-${settings.chainContract.take(8)}"

    val messageCodec  = new MessageCodec(peerDatabase)
    val trafficLogger = new TrafficLogger(settings.network.trafficLogger)
    def makePeerSynchronizer = if (settings.network.enablePeersExchange) {
      new PeerSynchronizer(peerDatabase, settings.network.peersBroadcastInterval)
    } else PeerSynchronizer.Disabled

    NS(
      applicationName,
      settings.network,
      peerDatabase,
      allChannels,
      peerInfo,
      Seq[ChannelHandlerAdapter](
        new LegacyFrameCodec(peerDatabase),
        messageCodec,
        trafficLogger,
        makePeerSynchronizer,
        historyReplier,
        messageObserver
      )
    )
  }
}
