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
    e2cNativeTransfersRootHash: Digest,
    lastC2ENativeTransferIndex: Long,
    e2cAssetTransfersRootHash: Digest,
    lastC2EAssetTransferIndex: Long,
    lastAssetRegistryIndex: Int
) extends L2BlockLike {
  override def toString: String =
    s"ContractBlock($hash, p=$parentHash, e=$epoch, h=$height, m=$minerRewardL2Address, c=$chainId, " +
      s"e2cn=${if (e2cNativeTransfersRootHash.isEmpty) "" else toHex(e2cNativeTransfersRootHash)}, c2en=$lastC2ENativeTransferIndex, " +
      s"e2ca=${if (e2cAssetTransfersRootHash.isEmpty) "" else toHex(e2cAssetTransfersRootHash)}, c2ea=$lastC2EAssetTransferIndex, " +
      s"lari=$lastAssetRegistryIndex)"
}

object ContractBlock {
  val E2CTransfersRootHashLength = 32 // bytes
}
