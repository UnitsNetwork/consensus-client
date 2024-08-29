package units.network

import units.network.BlocksObserverImpl.BlockWithChannel
import units.{BlockHash, NetworkL2Block}
import com.wavesplatform.network.ChannelObservable
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.Channel
import monix.eval.Task
import monix.execution.CancelableFuture

class TestBlocksObserver(override val getBlockStream: ChannelObservable[NetworkL2Block]) extends BlocksObserver with ScorexLogging {
  override def loadBlock(req: BlockHash): CancelableFuture[(Channel, NetworkL2Block)] = {
    log.debug(s"loadBlock($req)")
    CancelableFuture.never
  }

  def requestBlock(req: BlockHash): Task[BlockWithChannel] = {
    log.debug(s"requestBlock($req)")
    Task.never
  }
}
