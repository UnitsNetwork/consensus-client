package units.client.engine.model

import units.BlockHash
import play.api.libs.json.{Json, Writes}
import units.eth.EthAddress

/** @param topics
  *   Event signature hash and indexed event parameters
  * @see
  *   https://besu.hyperledger.org/stable/public-networks/reference/api#eth_getlogs
  */
case class GetLogsRequest(hash: BlockHash, address: EthAddress, topics: List[String], id: Int)
object GetLogsRequest {
  implicit val writes: Writes[GetLogsRequest] = (o: GetLogsRequest) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "eth_getLogs",
      "params" -> Json.arr(
        Json.obj(
          "blockHash" -> o.hash,
          "address"   -> o.address,
          "topics"    -> o.topics
        )
      ),
      "id" -> o.id
    )
  }
}
