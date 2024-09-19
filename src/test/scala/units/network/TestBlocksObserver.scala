package units.network

import com.wavesplatform.network.ChannelObservable
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.Channel
import monix.eval.Task
import monix.execution.CancelableFuture
import units.network.BlocksObserverImpl.BlockWithChannel
import units.{BlockHash, NetworkBlock}

class TestBlocksObserver(override val getBlockStream: ChannelObservable[NetworkBlock]) extends BlocksObserver with ScorexLogging {
  override def loadBlock(req: BlockHash): CancelableFuture[(Channel, NetworkBlock)] = {
    log.debug(s"loadBlock($req)")
    CancelableFuture.never
  }

  def requestBlock(req: BlockHash): Task[BlockWithChannel] = {
    log.debug(s"requestBlock($req)")
    Task.never
  }
}
