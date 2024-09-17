package units.client.engine.model

import org.web3j.abi.datatypes.generated.Uint256
import play.api.libs.functional.syntax.*
import play.api.libs.json.*
import play.api.libs.json.Format.GenericFormat
import units.BlockHash
import units.client.L2BlockLike
import units.eth.EthAddress
import units.util.HexBytesConverter.*

/** Block in EC API, not a payload of Engine API! See BlockHeader in besu.
  * @param timestamp
  *   In seconds, see ProcessableBlockHeader.timestamp comment <- SealableBlockHeader <- BlockHeader
  *   https://besu.hyperledger.org/stable/public-networks/reference/engine-api/objects#execution-payload-object tells about milliseconds
  */
case class EcBlock(
    hash: BlockHash,
    parentHash: BlockHash,
    stateRoot: String,
    height: Long,
    timestamp: Long,
    minerRewardL2Address: EthAddress,
    baseFeePerGas: Uint256,
    gasLimit: Long,
    gasUsed: Long,
    prevRandao: String,
    withdrawals: Vector[Withdrawal]
) extends L2BlockLike {
  override def toString: String =
    s"EcBlock($hash, p=$parentHash, h=$height, t=$timestamp, m=$minerRewardL2Address, w={${withdrawals.mkString(", ")}})"
}

object EcBlock {
  implicit val reads: Reads[EcBlock] = (
    (JsPath \ "hash").read[BlockHash] and
      (JsPath \ "parentHash").read[BlockHash] and
      (JsPath \ "stateRoot").read[String] and
      (JsPath \ "number").read[String].map(toLong) and
      (JsPath \ "timestamp").read[String].map(toLong) and
      (JsPath \ "miner").read[EthAddress] and
      (JsPath \ "baseFeePerGas").read[String].map(toUint256) and
      (JsPath \ "gasLimit").read[String].map(toLong) and
      (JsPath \ "gasUsed").read[String].map(toLong) and
      (JsPath \ "mixHash").read[String] and
      (JsPath \ "withdrawals").readWithDefault(Vector.empty[Withdrawal])
  )(EcBlock.apply _)
}
