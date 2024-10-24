package units.network.reward

import com.wavesplatform.account.KeyPair
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import units.client.contract.HasConsensusLayerDappTxHelpers
import units.client.engine.model.BlockNumber
import units.eth.{EthAddress, Gwei}
import units.network.BaseItTestSuite

import scala.concurrent.duration.DurationInt

class RewardTestSuite extends BaseItTestSuite with HasConsensusLayerDappTxHelpers {
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 30.seconds, interval = 1.second)

  // TODO move to base class
  override def currentHitSource: ByteStr     = ByteStr.empty
  override val chainContractAccount: KeyPair = mkKeyPair("devnet-1", 2)

  "L2-234 The reward for a previous epoch is in the first block withdrawals" in {
    log.info("Set script")
    waves1.api.broadcastAndWait(chainContract.setScript())

    log.info("Setup chain contract")
    val rewardAmount = Gwei.ofRawGwei(2_000_000_000L)
    val genesisBlock = ec1.engineApi.getBlockByNumber(BlockNumber.Number(0)).explicitGet().getOrElse(fail("No EL genesis block"))
    waves1.api.broadcastAndWait(
      chainContract
        .setup(
          genesisBlock = genesisBlock,
          elMinerReward = rewardAmount.amount.longValue(),
          daoAddress = None,
          daoReward = 0,
          invoker = chainContractAccount
        )
    )

    log.info("Waves miner #1 join")
    val miner1RewardAddress = EthAddress.unsafeFrom("0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f9")
    val joinTxnResult = waves1.api.broadcastAndWait(
      chainContract
        .join(
          minerAccount = mkKeyPair("devnet-1", 0),
          elRewardAddress = miner1RewardAddress
        )
    )

    val epoch1Number = joinTxnResult.height + 1 // First mined epoch
    val epoch2Number = joinTxnResult.height + 2

    log.info(s"Wait for #$epoch2Number epoch")
    waves1.api.waitForHeight(epoch2Number)

    log.info(s"Wait for epoch #$epoch2Number data on chain contract")
    val epoch2FirstContractBlock = eventually {
      waves1.chainContract.getEpochFirstBlock(epoch2Number).get
    }

    val epoch2FirstEcBlock = ec1.engineApi
      .getBlockByHash(epoch2FirstContractBlock.hash)
      .explicitGet()
      .getOrElse(fail(s"Can't find ${epoch2FirstContractBlock.hash}"))

    epoch2FirstEcBlock.withdrawals should have length 1

    withClue("Expected reward amount: ") {
      epoch2FirstEcBlock.withdrawals(0).amount shouldBe rewardAmount
    }

    withClue("Expected reward receiver: ") {
      epoch2FirstEcBlock.withdrawals(0).address shouldBe miner1RewardAddress
    }

    val epoch1FirstContractBlock = waves1.chainContract.getEpochFirstBlock(epoch1Number).getOrElse(fail(s"No first block of epoch $epoch1Number"))
    val epoch1FirstEcBlock = ec1.engineApi
      .getBlockByHash(epoch1FirstContractBlock.hash)
      .explicitGet()
      .getOrElse(fail(s"Can't find ${epoch1FirstContractBlock.hash}"))

    withClue("No reward for genesis block: ") {
      epoch1FirstEcBlock.withdrawals shouldBe empty
    }
  }
}
