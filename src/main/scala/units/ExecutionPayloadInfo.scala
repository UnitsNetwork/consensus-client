package units

import play.api.libs.json.JsObject
import units.client.engine.model.ExecutionPayload

case class ExecutionPayloadInfo(payload: ExecutionPayload, payloadJson: JsObject)
