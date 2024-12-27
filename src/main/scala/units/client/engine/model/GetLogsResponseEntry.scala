package units.client.engine.model

import play.api.libs.json.{Json, Reads}
import units.eth.{EthAddress, EthNumber}

/** @param topics
  *   List of hex values
  */
case class GetLogsResponseEntry(
    logIndex: EthNumber,
    address: EthAddress,
    data: String,         // Bytes
    topics: List[String], // TODO type
    transactionHash: String
)

object GetLogsResponseEntry {
  implicit val getLogsResponseEntryReads: Reads[GetLogsResponseEntry] = Json.reads
}
