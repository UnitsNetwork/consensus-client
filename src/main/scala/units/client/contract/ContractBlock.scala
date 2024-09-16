package units.client.contract

import com.wavesplatform.common.merkle.Digest
import com.wavesplatform.common.state.ByteStr
import units.BlockHash
import units.client.L2BlockLike
import units.eth.EthAddress
import units.util.HexBytesConverter.toHex

case class ContractBlock(
    hash: BlockHash,
    parentHash: BlockHash,
    epoch: Int,
    height: Long,
    generator: ByteStr,
    minerRewardL2Address: EthAddress,
    chainId: Long,
    elToClTransfersRootHash: Digest,
    lastClToElTransferIndex: Long
) extends L2BlockLike {
  override def toString: String =
    s"ContractBlock($hash, p=$parentHash, e=$epoch, h=$height, m=$minerRewardL2Address ($generator), c=$chainId, " +
      s"e2c=${if (elToClTransfersRootHash.isEmpty) "" else toHex(elToClTransfersRootHash)}, c2e=$lastClToElTransferIndex)"
}

object ContractBlock {
  val ElToClTransfersRootHashLength = 32 // bytes
}
