package units

import org.web3j.abi.datatypes.generated.Uint256
import units.client.TestEcClients
import units.client.engine.model.Withdrawal
import units.client.http.model.{EcBlock, GetLogsResponseEntry}
import units.eth.{EthAddress, EthereumConstants, Gwei}

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.FiniteDuration

// TODO TestEcBlocks
class TestEcBlockBuilder private (
    testEcClients: TestEcClients,
    elMinerDefaultReward: Gwei,
    private var block: EcBlock,
    parentBlock: EcBlock
) {
  def updateBlock(f: EcBlock => EcBlock): TestEcBlockBuilder = {
    block = f(block)
    this
  }

  def rewardPrevMiner(elWithdrawalIndex: Int = 0): TestEcBlockBuilder = {
    block = block.copy(withdrawals = Vector(Withdrawal(elWithdrawalIndex, parentBlock.minerRewardL2Address, elMinerDefaultReward)))
    this
  }

  def build(): EcBlock = block
  def buildAndSetLogs(logs: List[GetLogsResponseEntry] = Nil): EcBlock = {
    testEcClients.setBlockLogs(block.hash, Bridge.ElSentNativeEventTopic, logs)
    block
  }
}

object TestEcBlockBuilder {
  val emptyEcBlock = EcBlock(
    hash = createBlockHash("???"),
    parentHash = createBlockHash("???"),
    stateRoot = EthereumConstants.EmptyRootHashHex,
    height = 0,
    timestamp = 0,
    minerRewardL2Address = EthAddress.empty,
    baseFeePerGas = Uint256.DEFAULT,
    gasLimit = 0,
    gasUsed = 0,
    withdrawals = Vector.empty
  )

  def apply(
      testEcClients: TestEcClients,
      elMinerDefaultReward: Gwei,
      blockDelay: FiniteDuration,
      parent: EcBlock,
      block: EcBlock = TestEcBlockBuilder.emptyEcBlock
  ): TestEcBlockBuilder =
    new TestEcBlockBuilder(
      testEcClients,
      elMinerDefaultReward,
      block.copy(
        parentHash = parent.hash,
        height = parent.height + 1,
        timestamp = parent.timestamp + blockDelay.toSeconds
      ),
      parent
    )

  def createBlockHash(path: String): BlockHash = BlockHash(eth.hash(path.getBytes(StandardCharsets.UTF_8)))

  def createWithdrawal(index: Int, elRewardAddress: EthAddress, elMinerReward: Gwei): Withdrawal =
    Withdrawal(index, elRewardAddress, elMinerReward)
}
