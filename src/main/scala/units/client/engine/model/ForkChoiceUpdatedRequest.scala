package units.client.engine.model

import units.BlockHash
import units.client.engine.model.ForkChoiceUpdatedRequest.ForkChoiceAttributes
import units.eth.{EthAddress, EthereumConstants}
import units.util.HexBytesConverter.*
import play.api.libs.json.*

// https://github.com/ethereum/execution-apis/blob/main/src/engine/cancun.md#engine_forkchoiceupdatedv3
case class ForkChoiceUpdatedRequest(lastBlockHash: BlockHash, finalizedBlockHash: BlockHash, attrs: Option[ForkChoiceAttributes])

object ForkChoiceUpdatedRequest {
  case class ForkChoiceAttributes(unixEpochSeconds: Long, suggestedFeeRecipient: EthAddress, prevRandao: String, withdrawals: Vector[Withdrawal])

  implicit val writes: Writes[ForkChoiceUpdatedRequest] = (o: ForkChoiceUpdatedRequest) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "engine_forkchoiceUpdatedV3",
      "params" -> Json.arr(
        Json.obj("headBlockHash" -> o.lastBlockHash, "safeBlockHash" -> o.lastBlockHash, "finalizedBlockHash" -> o.finalizedBlockHash),
        o.attrs
          .map(attr =>
            Json.obj(
              "timestamp"             -> toHex(attr.unixEpochSeconds),
              "prevRandao"            -> attr.prevRandao,
              "suggestedFeeRecipient" -> attr.suggestedFeeRecipient,
              "withdrawals"           -> attr.withdrawals,
              "parentBeaconBlockRoot" -> EthereumConstants.EmptyRootHashHex
            )
          )
          .getOrElse[JsValue](JsNull)
      ),
      "id" -> 1
    )
  }
}
