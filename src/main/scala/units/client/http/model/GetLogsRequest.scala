package units.client.http.model

import units.BlockHash
import play.api.libs.json.{Json, Writes}

/** @param topics
  *   Event signature hash and indexed event parameters
  * @see
  *   https://besu.hyperledger.org/stable/public-networks/reference/api#eth_getlogs
  */
case class GetLogsRequest(hash: BlockHash, topics: List[String])
object GetLogsRequest {
  implicit val writes: Writes[GetLogsRequest] = (o: GetLogsRequest) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "eth_getLogs",
      "params" -> Json.arr(
        Json.obj(
          "blockHash" -> o.hash,
          "topics"    -> o.topics
        )
      ),
      "id" -> 1
    )
  }
}
