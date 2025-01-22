package units

import org.web3j.abi.datatypes.generated.Uint256
import units.client.TestEcClients
import units.client.engine.model.{EcBlock, GetLogsRequest, GetLogsResponseEntry, Withdrawal}
import units.el.{Bridge, IssuedTokenBridge}
import units.eth.{EthAddress, EthereumConstants, Gwei}

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.FiniteDuration

class TestEcBlockBuilder private (
    testEcClients: TestEcClients,
    elBridgeAddress: EthAddress,
    elMinerDefaultReward: Gwei,
    private var block: EcBlock,
    parentBlock: EcBlock
) {
  def updateBlock(f: EcBlock => EcBlock): this.type = {
    block = f(block)
    this
  }

  def rewardPrevMiner(elWithdrawalIndex: Int = 0): this.type = rewardMiner(parentBlock.minerRewardL2Address, elWithdrawalIndex)

  def rewardMiner(minerRewardL2Address: EthAddress, elWithdrawalIndex: Int = 0): this.type = {
    block = block.copy(withdrawals = Vector(Withdrawal(elWithdrawalIndex, minerRewardL2Address, elMinerDefaultReward)))
    this
  }

  def setLogs(nativeTopicLogs: List[GetLogsResponseEntry] = Nil, issuedTopicLogs: List[GetLogsResponseEntry] = Nil): this.type = {
    testEcClients.setBlockLogs(
      GetLogsRequest(block.hash, List(elBridgeAddress), List(Bridge.ElSentNativeEventTopic), 0),
      nativeTopicLogs
    )
    testEcClients.setBlockLogs(
      GetLogsRequest(block.hash, issuedTopicLogs.map(_.address), List(IssuedTokenBridge.ERC20BridgeFinalized.Topic), 0),
      issuedTopicLogs
    )
    this
  }

  def build(): EcBlock = block
  def buildAndSetLogs(nativeTopicLogs: List[GetLogsResponseEntry] = Nil, issuedTopicLogs: List[GetLogsResponseEntry] = Nil): EcBlock =
    setLogs(nativeTopicLogs, issuedTopicLogs).block
}

object TestEcBlockBuilder {
  def apply(
      testEcClients: TestEcClients,
      elBridgeAddress: EthAddress,
      elMinerDefaultReward: Gwei,
      blockDelay: FiniteDuration,
      parent: EcBlock
  ): TestEcBlockBuilder =
    new TestEcBlockBuilder(
      testEcClients,
      elBridgeAddress,
      elMinerDefaultReward,
      EcBlock(
        hash = createBlockHash("???"),
        parentHash = parent.hash,
        stateRoot = EthereumConstants.EmptyRootHashHex,
        height = parent.height + 1,
        timestamp = parent.timestamp + blockDelay.toSeconds,
        minerRewardL2Address = EthAddress.empty,
        baseFeePerGas = Uint256.DEFAULT,
        gasLimit = 0,
        gasUsed = 0,
        prevRandao = EthereumConstants.EmptyPrevRandaoHex,
        withdrawals = Vector.empty
      ),
      parent
    )

  def createBlockHash(path: String): BlockHash = BlockHash(eth.hash(path.getBytes(StandardCharsets.UTF_8)))
}
