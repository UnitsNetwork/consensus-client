package units.client.engine.model

import units.BlockHash
import play.api.libs.json.{Json, Writes}

case class GetBlockByHashRequest(hash: BlockHash, fullTxs: Boolean)
object GetBlockByHashRequest {
  implicit val writes: Writes[GetBlockByHashRequest] = (o: GetBlockByHashRequest) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "eth_getBlockByHash",
      "params"  -> Json.arr(o.hash, o.fullTxs),
      "id"      -> 1
    )
  }
}
