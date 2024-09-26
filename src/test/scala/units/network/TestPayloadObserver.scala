package units.network

import com.wavesplatform.account.PrivateKey
import com.wavesplatform.network.ChannelObservable
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.Channel
import monix.eval.Task
import monix.execution.CancelableFuture
import play.api.libs.json.JsObject
import units.network.PayloadObserverImpl.PayloadInfoWithChannel
import units.{BlockHash, ExecutionPayloadInfo}

class TestPayloadObserver(override val getPayloadStream: ChannelObservable[ExecutionPayloadInfo]) extends PayloadObserver with ScorexLogging {
  override def loadPayload(req: BlockHash): CancelableFuture[(Channel, ExecutionPayloadInfo)] = {
    log.debug(s"loadBlock($req)")
    CancelableFuture.never
  }

  def requestPayload(req: BlockHash): Task[PayloadInfoWithChannel] = {
    log.debug(s"requestBlock($req)")
    Task.never
  }

  override def broadcastSigned(payloadJson: JsObject, signer: PrivateKey): Either[String, PayloadMessage] = {
    log.debug(s"broadcastSigned($payloadJson, $signer)")
    PayloadMessage.signed(payloadJson, signer)
  }

  override def broadcast(hash: BlockHash): Unit = {
    log.debug(s"broadcast($hash)")
  }
}
