package units.client.contract

import com.wavesplatform.common.merkle.Digest
import units.BlockHash
import units.client.CommonBlockData
import units.eth.EthAddress
import units.util.HexBytesConverter.toHex

case class ContractBlock(
    hash: BlockHash,
    parentHash: BlockHash,
    epoch: Int,
    height: Long,
    minerRewardAddress: EthAddress,
    chainId: Long,
    e2cTransfersRootHash: Digest,
    lastC2ETransferIndex: Long
) extends CommonBlockData {
  override def toString: String =
    s"ContractBlock($hash, p=$parentHash, e=$epoch, h=$height, m=$minerRewardAddress, c=$chainId, " +
      s"e2c=${if (e2cTransfersRootHash.isEmpty) "" else toHex(e2cTransfersRootHash)}, c2e=$lastC2ETransferIndex)"
}

object ContractBlock {
  val E2CTransfersRootHashLength = 32 // bytes
}
