package units.client.engine.model

import units.client.engine.EngineApiClient.PayloadId
import play.api.libs.json.{Json, Reads}

case class ForkChoiceUpdatedResponse(payloadStatus: PayloadState, payloadId: Option[PayloadId])

object ForkChoiceUpdatedResponse {
  implicit val reads: Reads[ForkChoiceUpdatedResponse] = Json.reads
}
