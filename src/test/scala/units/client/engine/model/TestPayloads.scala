package units.client.engine.model

import play.api.libs.json.{JsObject, Json}
import units.util.HexBytesConverter.toHex

object TestPayloads {
  def toPayloadJson(payload: ExecutionPayload, prevRandao: String): JsObject = Json.obj(
    "blockHash"     -> payload.hash,
    "timestamp"     -> toHex(payload.timestamp),
    "blockNumber"   -> toHex(payload.height),
    "parentHash"    -> payload.parentHash,
    "stateRoot"     -> payload.stateRoot,
    "feeRecipient"  -> payload.feeRecipient,
    "prevRandao"    -> prevRandao,
    "baseFeePerGas" -> toHex(payload.baseFeePerGas),
    "gasLimit"      -> toHex(payload.gasLimit),
    "gasUsed"       -> toHex(payload.gasUsed),
    "withdrawals"   -> payload.withdrawals
  )
}
