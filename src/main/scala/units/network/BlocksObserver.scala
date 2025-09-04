package units.network

import com.wavesplatform.network.ChannelObservable
import units.network.BlocksObserverImpl.BlockWithChannel
import units.{BlockHash, NetworkL2Block}

import scala.concurrent.Future

trait BlocksObserver {
  def blockStream: ChannelObservable[NetworkL2Block]

  def requestBlockFromPeers(req: BlockHash): Future[BlockWithChannel]
}
