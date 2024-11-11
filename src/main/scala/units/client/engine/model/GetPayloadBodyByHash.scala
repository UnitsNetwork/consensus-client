package units.client.engine.model

import play.api.libs.json.{Json, Writes}
import units.BlockHash

case class GetPayloadBodyByHash(hash: BlockHash, id: Int)

object GetPayloadBodyByHash {
  implicit val writes: Writes[GetPayloadBodyByHash] = (o: GetPayloadBodyByHash) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "engine_getPayloadBodiesByHashV1",
      "params"  -> Json.arr(Json.arr(o.hash)),
      "id"      -> o.id
    )
  }
}
