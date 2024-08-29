package units.client.engine.model

import play.api.libs.json.{JsObject, Json, Reads}

case class GetPayloadResponse(executionPayload: JsObject)

object GetPayloadResponse {
  implicit val reads: Reads[GetPayloadResponse] = Json.reads
}
