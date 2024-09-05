package units.client.engine.model

import play.api.libs.json.{Json, Writes}

case class GetBlockByNumberRequest(number: String)
object GetBlockByNumberRequest {
  implicit val writes: Writes[GetBlockByNumberRequest] = (o: GetBlockByNumberRequest) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "eth_getBlockByNumber",
      "params"  -> Json.arr(o.number, false),
      "id"      -> 1
    )
  }
}
