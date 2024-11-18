package units

import com.wavesplatform.account.KeyPair
import com.wavesplatform.api.http.ApiError.ScriptExecutionError
import com.wavesplatform.common.state.ByteStr
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.docker.WavesNodeContainer

import scala.annotation.tailrec

class AlternativeChainTestSuite extends BaseDockerTestSuite {
  private val fiveBlocks = WavesNodeContainer.AverageBlockDelay * 5

  "L2-383 Start an alternative chain after not getting an EL-block" in {
    step("Wait miner #1 forge at least one block")
    def getLastContractBlock = chainContract.getLastBlockMeta(0).getOrElse(fail("Can't get last block"))
    eventually {
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
    @tailrec def broadcastConfirmation(maxAttempts: Int = 5): Unit = {
      if (maxAttempts == 0) fail("Can't broadcast an EL-block confirmation: all attempts are out")
      val lastContractBlock = getLastContractBlock
      val lastWavesBlock    = waves1.api.blockHeader(waves1.api.height).getOrElse(fail("Can't get current block header"))
      val txn = ChainContract.extendMainChain(
        minerAccount = miner21Account,
        blockHash = BlockHash("0x0000000000000000000000000000000000000000000000000000000000000001"),
        parentBlockHash = lastContractBlock.hash,
        e2cTransfersRootHashHex = EmptyE2CTransfersRootHashHex,
        lastC2ETransferIndex = -1,
        vrf = ByteStr.decodeBase58(lastWavesBlock.VRF).get
      )
      waves1.api.broadcast(txn) match {
        case Left(e) if e.error == ScriptExecutionError.Id =>
          log.debug(s"Failed to send an EL-block confirmation: $e")
          broadcastConfirmation(maxAttempts - 1)
        case Left(e) => fail(s"Can't broadcast an EL-block confirmation: $e")
        case _       => waves1.api.waitFor(txn.id())
      }
    }
    broadcastConfirmation()

    step("Wait miner #1 epoch")
    waitMinerEpoch(miner11Account)

    step("Checking an alternative chain started")
    eventually {
      chainContract.getChainInfo(1L) shouldBe defined
    }
  }

  private def waitMinerEpoch(minerAccount: KeyPair): Unit = {
    val expectedGenerator = minerAccount.toAddress
    eventually(timeout(fiveBlocks)) {
      val actualGenerator = chainContract.computedGenerator
      actualGenerator shouldBe expectedGenerator
    }
  }
}
