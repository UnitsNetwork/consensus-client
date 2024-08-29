package units.client.engine.model

import units.client.engine.EngineApiClient.PayloadId
import play.api.libs.json.{Json, Writes}

case class GetPayloadRequest(payloadId: PayloadId)

object GetPayloadRequest {
  implicit val writes: Writes[GetPayloadRequest] = (o: GetPayloadRequest) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "engine_getPayloadV3",
      "params"  -> Json.arr(o.payloadId),
      "id"      -> 1
    )
  }
}
