package units.client.contract

import units.client.contract.ContractFunction.*
import units.eth.{EthAddress, Gwei}

/** @note
  *   Make sure you have an activation gap: a new feature should not be activated suddenly during nearest blocks.
  */
case class ChainContractOptions(miningReward: Gwei, elBridgeAddress: EthAddress) {
  def startEpochChainFunction(epochNumber: Int, chainInfo: Option[ChainInfo]): ContractFunction = {
    chainInfo match {
      case Some(ci) => extendChainFunction(ci, epochNumber)
      case _        => startAltChainFunction(epochNumber)
    }
  }

  def appendFunction: ContractFunction =
    AppendBlock(V3)

  private def extendChainFunction(chainInfo: ChainInfo, epochNumber: Int): ContractFunction =
    if (chainInfo.isMain) extendMainChainFunction(epochNumber)
    else extendAltChainFunction(chainInfo.id, epochNumber)

  private def extendMainChainFunction(epochNumber: Int): ContractFunction =
    ExtendMainChain(epochNumber, V3)

  private def extendAltChainFunction(chainId: Long, epochNumber: Int): ContractFunction =
    ExtendAltChain(chainId, epochNumber, V3)

  private def startAltChainFunction(epochNumber: Int): ContractFunction =
    StartAltChain(epochNumber, V3)
}
