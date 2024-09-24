package units

import com.wavesplatform.common.state.ByteStr
import play.api.libs.json.JsObject
import units.client.engine.model.ExecutionPayload

case class ExecutionPayloadInfo(payload: ExecutionPayload, payloadJson: JsObject, signature: Option[ByteStr])
