package units.client.engine.model

import units.BlockHash
import play.api.libs.json.{Json, Writes}

case class GetPayloadBodyByHash(hash: BlockHash)

object GetPayloadBodyByHash {
  implicit val writes: Writes[GetPayloadBodyByHash] = (o: GetPayloadBodyByHash) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "engine_getPayloadBodiesByHashV1",
      "params"  -> Json.arr(Json.arr(o.hash)),
      "id"      -> 1
    )
  }
}
