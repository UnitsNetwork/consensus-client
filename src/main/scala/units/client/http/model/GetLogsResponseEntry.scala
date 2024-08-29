package units.client.http.model

import units.eth.EthAddress
import play.api.libs.json.{Json, Reads}

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
