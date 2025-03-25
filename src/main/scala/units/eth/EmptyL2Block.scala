package units.eth

import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.rlp.{RlpEncoder, RlpList, RlpString}
import play.api.libs.json.{JsObject, Json}
import units.BlockHash
import units.client.engine.model.{BlockOverrides, BlockStateCall, EcBlock, Withdrawal}
import units.el.DepositedTransaction
import units.util.HexBytesConverter

object EmptyL2Block {
  private val InternalBlockTimestampDiff = 1 // seconds

  def mkSimulateCall(
      parent: EcBlock,
      feeRecipient: EthAddress,
      time: Long,
      prevRandao: String,
      withdrawals: Seq[Withdrawal],
      depositedTransactions: Seq[DepositedTransaction]
  ): Seq[BlockStateCall] = Seq(
    BlockStateCall(
      BlockOverrides(
        parent.height + 1,
        calculateGasFee(parent.gasLimit, parent.baseFeePerGas, parent.gasUsed),
        feeRecipient,
        time,
        prevRandao,
        withdrawals
      ),
      depositedTransactions
    )
  )

  private val BaseFeeMaxChangeDenominator = 8
  private val ElasticityMultiplier        = 2

  // See an implemented algorithm org.hyperledger.besu.ethereum.mainnet.feemarket.{london, cancun, ...}
  // Current reference: LondonFeeMarket.computeBaseFee, because it didn't change in CancunFeeMarket.
  def calculateGasFee(parentGasLimit: Long, parentBaseFeePerGas: Uint256, parentGasUsed: Long): Uint256 = {
    val parentValue     = BigInt(parentBaseFeePerGas.getValue)
    val parentGasTarget = parentGasLimit / ElasticityMultiplier
    if (parentGasUsed == parentGasTarget) parentBaseFeePerGas
    else if (parentGasUsed > parentGasTarget) {
      val gasUsedDelta       = parentGasUsed - parentGasTarget
      val baseFeePerGasDelta = (parentValue * gasUsedDelta / parentGasTarget / BaseFeeMaxChangeDenominator).max(1)
      new Uint256((parentValue + baseFeePerGasDelta).bigInteger)
    } else {
      val gasUsedDelta       = parentGasTarget - parentGasUsed
      val baseFeePerGasDelta = (parentValue * gasUsedDelta / parentGasTarget / BaseFeeMaxChangeDenominator).toLong
      new Uint256((parentValue - baseFeePerGasDelta).bigInteger)
    }
  }
}
