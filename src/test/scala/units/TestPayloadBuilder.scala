package units

import org.web3j.abi.datatypes.generated.Uint256
import units.client.TestEcClients
import units.client.engine.model.{ExecutionPayload, GetLogsResponseEntry, Withdrawal}
import units.eth.{EthAddress, EthereumConstants, Gwei}

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.FiniteDuration

class TestPayloadBuilder private (
    testEcClients: TestEcClients,
    elBridgeAddress: EthAddress,
    elMinerDefaultReward: Gwei,
    private var payload: ExecutionPayload,
    parentPayload: ExecutionPayload
) {
  def updatePayload(f: ExecutionPayload => ExecutionPayload): TestPayloadBuilder = {
    payload = f(payload)
    this
  }

  def rewardPrevMiner(elWithdrawalIndex: Int = 0): TestPayloadBuilder = rewardMiner(parentPayload.minerRewardAddress, elWithdrawalIndex)

  def rewardMiner(minerRewardAddress: EthAddress, elWithdrawalIndex: Int = 0): TestPayloadBuilder = {
    payload = payload.copy(withdrawals = Vector(Withdrawal(elWithdrawalIndex, minerRewardAddress, elMinerDefaultReward)))
    this
  }

  def build(): ExecutionPayload = payload
  def buildAndSetLogs(logs: List[GetLogsResponseEntry] = Nil): ExecutionPayload = {
    testEcClients.setBlockLogs(payload.hash, elBridgeAddress, Bridge.ElSentNativeEventTopic, logs)
    payload
  }
}

object TestPayloadBuilder {
  def apply(
      testEcClients: TestEcClients,
      elBridgeAddress: EthAddress,
      elMinerDefaultReward: Gwei,
      blockDelay: FiniteDuration,
      parentPayload: ExecutionPayload
  ): TestPayloadBuilder =
    new TestPayloadBuilder(
      testEcClients,
      elBridgeAddress,
      elMinerDefaultReward,
      ExecutionPayload(
        hash = createBlockHash("???"),
        parentHash = parentPayload.hash,
        stateRoot = EthereumConstants.EmptyRootHashHex,
        height = parentPayload.height + 1,
        timestamp = parentPayload.timestamp + blockDelay.toSeconds,
        minerRewardAddress = EthAddress.empty,
        baseFeePerGas = Uint256.DEFAULT,
        gasLimit = 0,
        gasUsed = 0,
        prevRandao = EthereumConstants.EmptyPrevRandaoHex,
        withdrawals = Vector.empty
      ),
      parentPayload
    )

  def createBlockHash(path: String): BlockHash = BlockHash(eth.hash(path.getBytes(StandardCharsets.UTF_8)))
}
