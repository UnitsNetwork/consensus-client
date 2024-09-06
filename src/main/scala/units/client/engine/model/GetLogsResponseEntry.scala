package units.client.engine.model

import play.api.libs.json.{Json, Reads}
import units.eth.EthAddress

/** @param topics
  *   List of hex values
  */
case class GetLogsResponseEntry(
    address: EthAddress,
    data: String,        // Bytes
    topics: List[String] // TODO type
)

object GetLogsResponseEntry {
  implicit val getLogsResponseEntryReads: Reads[GetLogsResponseEntry] = Json.reads
}
