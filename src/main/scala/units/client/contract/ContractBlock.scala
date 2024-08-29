package units.client.contract

import com.wavesplatform.common.merkle.Digest
import com.wavesplatform.common.state.ByteStr
import units.BlockHash
import units.client.L2BlockLike
import units.eth.EthAddress

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
) extends L2BlockLike

object ContractBlock {
  val ElToClTransfersRootHashLength = 32 // bytes
}
