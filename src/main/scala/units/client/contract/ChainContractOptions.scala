package units.client.contract

import com.wavesplatform.common.state.ByteStr
import units.BlockHash
import units.client.contract.ContractFunction.*
import units.eth.{EthAddress, Gwei}

/** @note
  *   Make sure you have an activation gap: a new feature should not be activated suddenly during nearest blocks.
  */
case class ChainContractOptions(miningReward: Gwei, elBridgeAddress: EthAddress) {
  def startEpochChainFunction(reference: BlockHash, vrf: ByteStr, chainInfo: Option[ChainInfo]): ContractFunction = {
    chainInfo match {
      case Some(ci) => extendChainFunction(reference, vrf, ci)
      case _        => startAltChainFunction(reference, vrf)
    }
  }

  def appendFunction(reference: BlockHash): AppendBlock =
    AppendBlock(reference)

  private def extendChainFunction(reference: BlockHash, vrf: ByteStr, chainInfo: ChainInfo): ContractFunction =
    if (chainInfo.isMain) extendMainChainFunction(reference, vrf)
    else extendAltChainFunction(reference, vrf, chainInfo.id)

  private def extendMainChainFunction(reference: BlockHash, vrf: ByteStr): ContractFunction =
    ExtendMainChain(reference, vrf)

  private def extendAltChainFunction(reference: BlockHash, vrf: ByteStr, chainId: Long): ContractFunction =
    ExtendAltChain(reference, vrf, chainId)

  private def startAltChainFunction(reference: BlockHash, vrf: ByteStr): ContractFunction =
    StartAltChain(reference, vrf)
}
