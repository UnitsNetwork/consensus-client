package units.client.engine.model

import play.api.libs.json.*
import units.BlockHash
import units.client.engine.model.ForkchoiceUpdatedRequest.ForkChoiceAttributes
import units.eth.{EthAddress, EthereumConstants}
import units.util.HexBytesConverter.*

// https://github.com/ethereum/execution-apis/blob/main/src/engine/cancun.md#engine_forkchoiceupdatedv3
case class ForkchoiceUpdatedRequest(lastBlockHash: BlockHash, finalizedBlockHash: BlockHash, attrs: Option[ForkChoiceAttributes], id: Int)

object ForkchoiceUpdatedRequest {
  // TODO Type of transactions (signed transactions hex)
  case class ForkChoiceAttributes(
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal],
      transactions: Vector[String]
  )

  implicit val writes: Writes[ForkchoiceUpdatedRequest] = (o: ForkchoiceUpdatedRequest) => {
    Json.obj(
      "jsonrpc" -> "2.0",
      "method"  -> "engine_forkchoiceUpdatedV3",
      "params" -> Json.arr(
        Json.obj("headBlockHash" -> o.lastBlockHash, "safeBlockHash" -> o.finalizedBlockHash, "finalizedBlockHash" -> o.finalizedBlockHash),
        o.attrs
          .map(attr =>
            Json.obj(
              "timestamp"             -> toHex(attr.unixEpochSeconds),
              "prevRandao"            -> attr.prevRandao,
              "suggestedFeeRecipient" -> attr.suggestedFeeRecipient,
              "withdrawals"           -> attr.withdrawals,
              "parentBeaconBlockRoot" -> EthereumConstants.EmptyRootHashHex,
              "transactions"          -> attr.transactions
            )
          )
          .getOrElse[JsValue](JsNull)
      ),
      "id" -> o.id
    )
  }
}
