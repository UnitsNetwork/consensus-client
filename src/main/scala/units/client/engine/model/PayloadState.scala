package units.client.engine.model

import units.BlockHash
import play.api.libs.json.{Json, Reads}

case class PayloadState(status: PayloadStatus, latestValidHash: Option[BlockHash], validationError: Option[String])

object PayloadState {
  implicit val reads: Reads[PayloadState] = Json.reads
}
