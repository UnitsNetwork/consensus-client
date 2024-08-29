package units

import units.client.engine.model.Withdrawal
import units.client.http.model.EcBlock
import units.eth.{EthAddress, EthereumConstants, Gwei}
import com.wavesplatform.utils.Time
import org.web3j.abi.datatypes.generated.Uint256

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.FiniteDuration

trait HasCreateEcBlock {
  def blockDelay: FiniteDuration
  def testTime: Time
  def elMinerDefaultReward: Gwei

  def createBlockHash(id: String): BlockHash = BlockHash(eth.hash(id.getBytes(StandardCharsets.UTF_8)))

  def createNextEcBlock(
      hash: BlockHash,
      parent: EcBlock,
      stateRoot: String = EthereumConstants.EmptyRootHashHex,
      timestampInMillis: Long = 0,
      minerRewardL2Address: EthAddress = EthAddress.empty,
      baseFeePerGas: Uint256 = Uint256.DEFAULT,
      gasLimit: Long = 0,
      gasUsed: Long = 0,
      withdrawals: Vector[Withdrawal] = Vector.empty
  ): EcBlock = createEcBlock(
    hash = hash,
    parentHash = parent.hash,
    stateRoot = stateRoot,
    height = parent.height + 1,
    timestampInMillis = if (timestampInMillis == 0) parent.timestamp * 1000 + blockDelay.toMillis else timestampInMillis,
    minerRewardL2Address = minerRewardL2Address,
    baseFeePerGas = baseFeePerGas,
    gasLimit = gasLimit,
    gasUsed = gasUsed,
    withdrawals = withdrawals
  )

  def createEcBlock(
      hash: BlockHash,
      parentHash: BlockHash,
      height: Long,
      stateRoot: String = EthereumConstants.EmptyRootHashHex,
      timestampInMillis: Long = testTime.getTimestamp(),
      minerRewardL2Address: EthAddress = EthAddress.empty,
      baseFeePerGas: Uint256 = Uint256.DEFAULT,
      gasLimit: Long = 0,
      gasUsed: Long = 0,
      withdrawals: Vector[Withdrawal] = Vector.empty
  ): EcBlock = EcBlock(
    hash = hash,
    parentHash = parentHash,
    stateRoot = stateRoot,
    height = height,
    timestamp = timestampInMillis / 1000,
    minerRewardL2Address = minerRewardL2Address,
    baseFeePerGas = baseFeePerGas,
    gasLimit = gasLimit,
    gasUsed = gasUsed,
    withdrawals = withdrawals
  )

  def createWithdrawal(index: Int, elRewardAddress: EthAddress, elMinerReward: Gwei = elMinerDefaultReward): Withdrawal =
    Withdrawal(index, elRewardAddress, elMinerReward)
}
