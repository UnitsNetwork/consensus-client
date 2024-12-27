package units.client.engine.model

import play.api.libs.json.{JsObject, Json, Writes}
import units.BlockHash
import units.eth.EthAddress

/** @param topics
  *   Event signature hash and indexed event parameters
  * @see
  *   https://ethereum.org/en/developers/docs/apis/json-rpc/#eth_getlogs
  */
case class GetLogsRequest(blockHash: BlockHash, addresses: List[EthAddress], topics: List[String], id: Int)
object GetLogsRequest {
  implicit val writes: Writes[GetLogsRequest] = (o: GetLogsRequest) => {
    val addressJson = o.addresses match {
      case Nil            => JsObject.empty
      case address :: Nil => Json.obj("address" -> address)
      case addresses      => Json.obj("address" -> addresses)
    }

    val topicsJson = if (o.topics.isEmpty) JsObject.empty else Json.obj("topics" -> o.topics)

    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "eth_getLogs",
      "params" -> Json.arr(
        Json.obj("blockHash" -> o.blockHash) ++ addressJson ++ topicsJson
      ),
      "id" -> o.id
    )
  }
}
