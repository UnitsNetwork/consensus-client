package units.network

import units.network.BlocksObserverImpl.BlockWithChannel
import com.wavesplatform.network.ChannelObservable
import monix.eval.Task
import monix.execution.CancelableFuture
import units.{BlockHash, NetworkBlock}

trait BlocksObserver {
  def getBlockStream: ChannelObservable[NetworkBlock]

  def requestBlock(req: BlockHash): Task[BlockWithChannel]

  def loadBlock(req: BlockHash): CancelableFuture[BlockWithChannel]
}
