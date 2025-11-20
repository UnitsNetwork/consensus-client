package units.client.contract

import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.state.Height
import units.BlockHash
import units.client.contract.ContractFunction.*
import units.eth.{EthAddress, Gwei}

case class ChainContractOptions(
    miningReward: ValueAtEpoch[Gwei],
    elNativeBridgeAddress: EthAddress,
    elStandardBridgeAddress: Option[EthAddress],
    assetTransfersActivationEpoch: Height,
    blockDelayInSeconds: ValueAtEpoch[Int]
) {
  def bridgeAddresses(epoch: Height): List[EthAddress] = {
    val before = List(elNativeBridgeAddress)
    if (epoch < assetTransfersActivationEpoch) before
    else elStandardBridgeAddress.toList ::: before
  }

  def startEpochChainFunction(epoch: Height, reference: BlockHash, vrf: ByteStr, chainInfo: Option[ChainInfo]): ContractFunction =
    chainInfo match {
      case Some(chainInfo) =>
        if (chainInfo.isMain) ExtendMainChain(reference, vrf, versionOf(epoch))
        else ExtendAltChain(reference, vrf, chainInfo.id, versionOf(epoch))

      case _ =>
        StartAltChain(reference, vrf, versionOf(epoch))
    }

  def appendFunction(epoch: Height, reference: BlockHash): AppendBlock =
    AppendBlock(reference, versionOf(epoch))

  private def versionOf(epoch: Height): Int = if (epoch < assetTransfersActivationEpoch) 1 else 2
}

case class ValueAtEpoch[A](oldValue: A, newValue: A, changeAtEpoch: Height) {
  def valueAtEpoch(epoch: Height): A = if epoch < changeAtEpoch then oldValue else newValue
}
