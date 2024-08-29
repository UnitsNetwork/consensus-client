package units.client

import com.wavesplatform.common.state.ByteStr
import units.BlockHash
import units.eth.{EthAddress, EthereumConstants}
import units.util.HexBytesConverter.toByteStr

trait L2BlockLike {
  def hash: BlockHash
  def parentHash: BlockHash
  def height: Long
  def minerRewardL2Address: EthAddress

  lazy val hashByteStr: ByteStr       = toByteStr(hash)
  lazy val parentHashByteStr: ByteStr = toByteStr(parentHash)

  def referencesGenesis: Boolean = height == EthereumConstants.GenesisBlockHeight + 1
}
