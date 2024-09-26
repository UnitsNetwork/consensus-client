package units.network

import com.wavesplatform.account.PrivateKey
import monix.execution.CancelableFuture
import monix.reactive.Observable
import play.api.libs.json.JsObject
import units.{BlockHash, ExecutionPayloadInfo}

trait PayloadObserver {
  def getPayloadStream: Observable[ExecutionPayloadInfo]

  def loadPayload(req: BlockHash): CancelableFuture[ExecutionPayloadInfo]

  def broadcastSigned(payloadJson: JsObject, signer: PrivateKey): Either[String, PayloadMessage]

  def broadcast(hash: BlockHash): Unit
}
