package units.client

import units.BlockHash
import units.eth.{EthAddress, EthereumConstants}

trait CommonBlockData {
  def hash: BlockHash
  def parentHash: BlockHash
  def height: Long
  def minerRewardAddress: EthAddress

  def referencesGenesis: Boolean = height == EthereumConstants.GenesisBlockHeight + 1
}
