package units

import com.wavesplatform.common.utils.EitherExt2
import units.client.engine.model.BlockNumber

class RewardTestSuite extends OneNodeTestSuite {
  "L2-234 The reward for a previous epoch is in the first block withdrawals" in {
    val epoch1FirstEcBlock = eventually {
      ec1.engineApi.getBlockByNumber(BlockNumber.Number(1)).explicitGet().get
    }

    withClue("No reward for genesis block: ") {
      epoch1FirstEcBlock.withdrawals shouldBe empty
    }

    val epoch1FirstContractBlock = eventually {
      waves1.chainContract.getBlock(epoch1FirstEcBlock.hash).getOrElse(fail(s"No first block ${epoch1FirstEcBlock.hash} confirmation"))
    }

    val epoch1Number = epoch1FirstContractBlock.epoch
    val epoch2Number = epoch1Number + 1

    log.info(s"Wait for next epoch #$epoch2Number")
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
  }
}
