package units.client.engine.model

import play.api.libs.json.{Json, Writes}
import units.BlockHash

case class GetBlockByHashRequest(hash: BlockHash)
object GetBlockByHashRequest {
  implicit val writes: Writes[GetBlockByHashRequest] = (o: GetBlockByHashRequest) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "eth_getBlockByHash",
      "params"  -> Json.arr(o.hash, false),
      "id"      -> 1
    )
  }
}
