package units.client.engine.model

import units.BlockHash
import play.api.libs.json.{Json, Reads}

case class PayloadStatus(status: String, latestValidHash: Option[BlockHash], validationError: Option[String])

object PayloadStatus {
  implicit val reads: Reads[PayloadStatus] = Json.reads
}
