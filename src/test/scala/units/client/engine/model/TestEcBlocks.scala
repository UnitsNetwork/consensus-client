package units.client.engine.model

import com.wavesplatform.account.SeedKeyPair
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import play.api.libs.json.{JsObject, Json}
import units.NetworkL2Block
import units.util.HexBytesConverter.toHex

object TestEcBlocks {
  def toNetworkBlock(ecBlock: EcBlock, miner: SeedKeyPair, prevRandao: String): NetworkL2Block =
    NetworkL2Block.signed(TestEcBlocks.toPayload(ecBlock, prevRandao), miner.privateKey).explicitGet()

  def toPayload(ecBlock: EcBlock, prevRandao: String): JsObject = Json.obj(
    "blockHash"     -> ecBlock.hash,
    "timestamp"     -> toHex(ecBlock.timestamp),
    "blockNumber"   -> toHex(ecBlock.height),
    "parentHash"    -> ecBlock.parentHash,
    "stateRoot"     -> ecBlock.stateRoot,
    "feeRecipient"  -> ecBlock.minerRewardL2Address,
    "prevRandao"    -> prevRandao,
    "baseFeePerGas" -> toHex(ecBlock.baseFeePerGas),
    "gasLimit"      -> toHex(ecBlock.gasLimit),
    "gasUsed"       -> toHex(ecBlock.gasUsed),
    "withdrawals"   -> ecBlock.withdrawals
  )
}
