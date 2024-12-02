package units.client.engine.model

import play.api.libs.json.{Json, Writes}
import units.client.engine.EngineApiClient.PayloadId

case class GetPayloadRequest(payloadId: PayloadId, id: Int)

object GetPayloadRequest {
  implicit val writes: Writes[GetPayloadRequest] = (o: GetPayloadRequest) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "engine_getPayloadV3",
      "params"  -> Json.arr(o.payloadId),
      "id"      -> o.id
    )
  }
}
