package units.client.engine.model

import play.api.libs.json.{Json, Writes}
import units.BlockHash

case class GetBlockByHashRequest(hash: BlockHash, fullTransactionObjects: Boolean, id: Int)
object GetBlockByHashRequest {
  implicit val writes: Writes[GetBlockByHashRequest] = (o: GetBlockByHashRequest) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "eth_getBlockByHash",
      "params"  -> Json.arr(o.hash, o.fullTransactionObjects),
      "id"      -> o.id
    )
  }
}
