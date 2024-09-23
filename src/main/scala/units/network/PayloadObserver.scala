package units.network

import units.network.PayloadObserverImpl.PayloadWithChannel
import com.wavesplatform.network.ChannelObservable
import monix.eval.Task
import monix.execution.CancelableFuture
import units.BlockHash

trait PayloadObserver {
  def getPayloadStream: ChannelObservable[PayloadMessage]

  def requestPayload(req: BlockHash): Task[PayloadWithChannel]

  def loadPayload(req: BlockHash): CancelableFuture[PayloadWithChannel]
}
