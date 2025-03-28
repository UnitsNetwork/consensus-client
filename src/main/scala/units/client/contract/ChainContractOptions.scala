package units.client.contract

import com.wavesplatform.common.state.ByteStr
import units.BlockHash
import units.client.contract.ContractFunction.*
import units.eth.{EthAddress, Gwei}

/** @note
  *   Make sure you have an activation gap: a new feature should not be activated suddenly during nearest blocks.
  */
case class ChainContractOptions(
    miningReward: Gwei,
    elNativeBridgeAddress: EthAddress,
    elStandardBridgeAddress: Option[EthAddress],
    assetTransfersActivationEpoch: Long
) {
  def bridgeAddresses(epoch: Int): List[EthAddress] = {
    val before = List(elNativeBridgeAddress)
    if (epoch < assetTransfersActivationEpoch) before
    else elStandardBridgeAddress.toList ::: before
  }

  def startEpochChainFunction(epoch: Int, reference: BlockHash, vrf: ByteStr, chainInfo: Option[ChainInfo]): ContractFunction =
    chainInfo match {
      case Some(chainInfo) =>
        if (chainInfo.isMain) ExtendMainChain(reference, vrf, versionOf(epoch))
        else ExtendAltChain(reference, vrf, chainInfo.id, versionOf(epoch))

      case _ =>
        StartAltChain(reference, vrf, versionOf(epoch))
    }

  def appendFunction(epoch: Int, reference: BlockHash): AppendBlock =
    AppendBlock(reference, versionOf(epoch))

  private def versionOf(epoch: Int): Int = if (epoch < assetTransfersActivationEpoch) 1 else 2
}
