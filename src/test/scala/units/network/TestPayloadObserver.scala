package units.network

import com.wavesplatform.network.ChannelObservable
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.Channel
import monix.eval.Task
import monix.execution.CancelableFuture
import units.network.PayloadObserverImpl.PayloadWithChannel
import units.{BlockHash, NetworkBlock}

class TestPayloadObserver(override val getPayloadStream: ChannelObservable[NetworkBlock]) extends PayloadObserver with ScorexLogging {
  override def loadPayload(req: BlockHash): CancelableFuture[(Channel, NetworkBlock)] = {
    log.debug(s"loadBlock($req)")
    CancelableFuture.never
  }

  def requestPayload(req: BlockHash): Task[PayloadWithChannel] = {
    log.debug(s"requestBlock($req)")
    Task.never
  }
}
