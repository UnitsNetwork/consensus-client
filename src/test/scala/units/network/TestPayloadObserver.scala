package units.network

import com.wavesplatform.account.{PrivateKey, PublicKey}
import com.wavesplatform.network.ChannelGroupExt
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.group.DefaultChannelGroup
import monix.execution.CancelableFuture
import monix.reactive.Observable
import play.api.libs.json.JsObject
import units.eth.EthAddress
import units.{BlockHash, ExecutionPayloadInfo}

import java.util.concurrent.ConcurrentHashMap

class TestPayloadObserver(messages: Observable[PayloadMessage], allChannels: DefaultChannelGroup) extends PayloadObserver with ScorexLogging {

  private val lastPayloadMessages: ConcurrentHashMap[BlockHash, PayloadMessage] =
    new ConcurrentHashMap[BlockHash, PayloadMessage]()

  override def loadPayload(req: BlockHash): CancelableFuture[ExecutionPayloadInfo] = {
    log.debug(s"loadBlock($req)")
    CancelableFuture.never
  }

  override def broadcastSigned(payloadJson: JsObject, signer: PrivateKey): Either[String, PayloadMessage] = {
    log.debug(s"broadcastSigned($payloadJson, $signer)")
    val pm = PayloadMessage.signed(payloadJson, signer)
    allChannels.broadcast(pm)
    pm
  }

  override def broadcast(hash: BlockHash): Unit = {
    log.debug(s"broadcast($hash)")
    Option(lastPayloadMessages.get(hash)).foreach { pm =>
      allChannels.broadcast(pm)
    }
    lastPayloadMessages.remove(hash)
  }

  override def updateMinerPublicKeys(newKeys: Map[EthAddress, PublicKey]): Unit = {
    log.debug(s"updateMinerPublicKeys($newKeys)")
  }

  override def getPayloadStream: Observable[ExecutionPayloadInfo] = {
    log.debug("getPayloadStream")
    messages.concatMapIterable { pm =>
      pm.payloadInfo match {
        case Right(epi) =>
          lastPayloadMessages.put(pm.hash, pm)
          List(epi)
        case Left(_) => List.empty
      }
    }
  }
}
