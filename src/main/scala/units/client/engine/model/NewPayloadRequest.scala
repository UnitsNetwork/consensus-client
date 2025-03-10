package units.client.engine.model

import units.eth.EthereumConstants
import play.api.libs.json.{JsObject, Json, Writes}

case class NewPayloadRequest(payload: JsObject, id: Int)

object NewPayloadRequest {
  implicit val writes: Writes[NewPayloadRequest] = (o: NewPayloadRequest) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "engine_newPayloadV3",
      "params" -> Json.arr(
        o.payload - "parentBeaconBlockRoot", // Otherwise we get: Unrecognized field "parentBeaconBlockRoot"
        Json.arr(),
        EthereumConstants.EmptyBlockHashHex
      ),
      "id" -> o.id
    )
  }
}
