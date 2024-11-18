package units

import com.wavesplatform.api.LoggingBackend.LoggingOptions
import com.wavesplatform.api.http.ApiError.ScriptExecutionError
import com.wavesplatform.common.state.ByteStr
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex

import scala.annotation.tailrec

class AlternativeChainTestSuite extends BaseDockerTestSuite {
  "L2-383 Start an alternative chain after not getting an EL-block" in {
    step("Wait miner #1 forge at least one block")
    chainContract.waitForHeight(1L)

    step("EL miner #2 join")
    waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = miner21Account,
        elRewardAddress = miner21RewardAddress
      )
    )

    step("Wait miner #2 epoch and issue a block confirmation")
    broadcastElBlockConfirmation()

    step("Wait miner #1 epoch")
    chainContract.waitForMinerEpoch(miner11Account)

    step("Checking an alternative chain started")
    chainContract.waitForChainId(1L)
  }

  @tailrec private def broadcastElBlockConfirmation(maxAttempts: Int = 5)(implicit
      loggingOptions: LoggingOptions = LoggingOptions(logCall = false, logRequest = false)
  ): Unit = {
    if (maxAttempts == 0) fail("Can't broadcast an EL-block confirmation: all attempts are out")

    chainContract.waitForMinerEpoch(miner21Account)
    val lastContractBlock = chainContract.getLastBlockMeta(0).value
    val lastWavesBlock    = waves1.api.blockHeader(waves1.api.height).value
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
        broadcastElBlockConfirmation(maxAttempts - 1)
      case Left(e) => fail(s"Can't broadcast an EL-block confirmation: $e")
      case _       => waves1.api.waitForSucceeded(txn.id())
    }
  }
}
