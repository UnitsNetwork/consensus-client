package units.client.contract

import com.wavesplatform.common.merkle.Digest
import units.BlockHash
import units.client.L2BlockLike
import units.eth.EthAddress
import units.util.HexBytesConverter.toHex

case class ContractBlock(
    hash: BlockHash,
    parentHash: BlockHash,
    epoch: Int,
    height: Long,
    minerRewardL2Address: EthAddress,
    chainId: Long,
    e2cTransfersRootHash: Digest,
    lastC2ETransferIndex: Long,
    lastAssetRegistryIndex: Int
) extends L2BlockLike {
  override def toString: String =
    s"ContractBlock($hash, p=$parentHash, e=$epoch, h=$height, m=$minerRewardL2Address, c=$chainId, " +
      s"e2c=${if (e2cTransfersRootHash.isEmpty) "" else toHex(e2cTransfersRootHash)}, c2e=$lastC2ETransferIndex, " +
      s"lari=$lastAssetRegistryIndex)"
}

object ContractBlock {
  val E2CTransfersRootHashLength = 32 // bytes
}
