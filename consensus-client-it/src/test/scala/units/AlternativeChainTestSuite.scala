package units

import com.wavesplatform.account.KeyPair
import com.wavesplatform.common.state.ByteStr
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.docker.WavesNodeContainer

class AlternativeChainTestSuite extends OneNodeTestSuite {
  "L2-383 Start an alternative chain after not getting an EL-block" in {
    step("EL miner #2 join")
    waves1.api.broadcastAndWait(
      chainContract.join(
        minerAccount = miner21Account,
        elRewardAddress = miner21RewardAddress
      )
    )

    step("Wait miner #2 epoch")
    waitMinerEpoch(miner21Account)

    step("Issue miner #2 block confirmation")
    val lastContractBlock = waves1.chainContract.getLastBlockMeta(0).getOrElse(fail("Can't get last block"))
    val lastWavesBlock    = waves1.api.blockHeader(waves1.api.height).getOrElse(fail("Can't get current block header"))
    waves1.api.broadcastAndWait(
      chainContract.extendMainChain(
        minerAccount = miner21Account,
        blockHash = BlockHash("0x0000000000000000000000000000000000000000000000000000000000000001"),
        parentBlockHash = lastContractBlock.hash,
        e2cTransfersRootHashHex = EmptyE2CTransfersRootHashHex,
        lastC2ETransferIndex = -1,
        vrf = ByteStr.decodeBase58(lastWavesBlock.VRF).get
      )
    )

    step("Wait miner #1 epoch")
    waitMinerEpoch(miner11Account)

    step("Checking an alternative chain started")
    retry {
      waves1.chainContract.getChainInfo(1L).getOrElse(fail("Can't get an alternative chain info"))
    }
  }

  private def waitMinerEpoch(minerAccount: KeyPair): Unit = {
    val expectedGenerator = minerAccount.toAddress
    retry {
      val actualGenerator = waves1.chainContract.computedGenerator
      if (actualGenerator != expectedGenerator) fail(s"Expected $expectedGenerator generator, got $actualGenerator")
    }(patienceConfig.copy(timeout = WavesNodeContainer.AverageBlockDelay * 5))
  }
}
