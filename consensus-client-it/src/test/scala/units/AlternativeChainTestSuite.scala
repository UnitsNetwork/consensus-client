package units

import com.wavesplatform.account.KeyPair
import com.wavesplatform.common.state.ByteStr
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.docker.WavesNodeContainer

class AlternativeChainTestSuite extends BaseDockerTestSuite {
  private val waitFiveBlocksAtMax = patienceConfig.copy(timeout = WavesNodeContainer.AverageBlockDelay * 5)

  "L2-383 Start an alternative chain after not getting an EL-block" in {
    step("Wait miner #1 forge at least one block")
    def getLastContractBlock = chainContract.getLastBlockMeta(0).getOrElse(fail("Can't get last block"))
    retry {
      getLastContractBlock.height should be > 0L
    }

    step("EL miner #2 join")
    waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = miner21Account,
        elRewardAddress = miner21RewardAddress
      )
    )

    step("Wait miner #2 epoch")
    waitMinerEpoch(miner21Account)

    step("Issue miner #2 block confirmation")
    val lastContractBlock = getLastContractBlock
    val lastWavesBlock    = waves1.api.blockHeader(waves1.api.height).getOrElse(fail("Can't get current block header"))
    waves1.api.broadcastAndWait(
      ChainContract.extendMainChain(
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
      chainContract.getChainInfo(1L).getOrElse(fail("Can't get an alternative chain info"))
    }
  }

  private def waitMinerEpoch(minerAccount: KeyPair): Unit = {
    val expectedGenerator = minerAccount.toAddress
    retry {
      val actualGenerator = chainContract.computedGenerator
      if (actualGenerator != expectedGenerator) fail(s"Expected $expectedGenerator generator, got $actualGenerator")
    }(waitFiveBlocksAtMax)
  }
}
