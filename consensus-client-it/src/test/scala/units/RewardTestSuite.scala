package units

import com.wavesplatform.common.utils.EitherExt2
import units.client.engine.model.BlockNumber

class RewardTestSuite extends BaseDockerTestSuite {
  "L2-234 The reward for a previous epoch is in the first block withdrawals" in {
    val epoch1FirstEcBlock = retry {
      ec1.engineApi.getBlockByNumber(BlockNumber.Number(1)).explicitGet().get
    }

    withClue("No reward for genesis block: ") {
      epoch1FirstEcBlock.withdrawals shouldBe empty
    }

    val epoch1FirstContractBlock = retry {
      chainContract.getBlock(epoch1FirstEcBlock.hash).getOrElse(failRetry(s"No first block ${epoch1FirstEcBlock.hash} confirmation"))
    }

    val epoch1Number = epoch1FirstContractBlock.epoch
    val epoch2Number = epoch1Number + 1

    waves1.api.waitForHeight(epoch2Number)

    step(s"Wait for epoch #$epoch2Number data on chain contract")
    val epoch2FirstContractBlock = retry {
      chainContract.getEpochFirstBlock(epoch2Number).get
    }

    val epoch2FirstEcBlock = ec1.engineApi
      .getBlockByHash(epoch2FirstContractBlock.hash)
      .explicitGet()
      .getOrElse(failRetry(s"Can't find ${epoch2FirstContractBlock.hash}"))

    epoch2FirstEcBlock.withdrawals should have length 1

    withClue("Expected reward amount: ") {
      epoch2FirstEcBlock.withdrawals(0).amount shouldBe rewardAmount
    }

    withClue("Expected reward receiver: ") {
      epoch2FirstEcBlock.withdrawals(0).address shouldBe miner11RewardAddress
    }
  }
}
