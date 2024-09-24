package units.client.engine.model

import com.wavesplatform.account.SeedKeyPair
import com.wavesplatform.common.utils.EitherExt2
import play.api.libs.json.{JsObject, Json}
import units.NetworkBlock
import units.util.HexBytesConverter.toHex

object TestPayloads {
  def toNetworkBlock(payload: ExecutionPayload, miner: SeedKeyPair, prevRandao: String): NetworkBlock =
    NetworkBlock.signed(TestPayloads.toPayloadJson(payload, prevRandao), miner.privateKey).explicitGet()

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
