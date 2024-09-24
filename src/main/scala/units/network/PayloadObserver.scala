package units.network

import units.network.PayloadObserverImpl.PayloadInfoWithChannel
import com.wavesplatform.network.ChannelObservable
import monix.execution.CancelableFuture
import units.{BlockHash, ExecutionPayloadInfo}

trait PayloadObserver {
  def getPayloadStream: ChannelObservable[ExecutionPayloadInfo]

  def loadPayload(req: BlockHash): CancelableFuture[PayloadInfoWithChannel]
}
