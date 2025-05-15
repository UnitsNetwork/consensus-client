package units

import com.wavesplatform.account.Address
import com.wavesplatform.state.{Height, IntegerDataEntry, StringDataEntry}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.transaction.TxHelpers

class EmptyEpochMinerEvictionTestSuite extends BaseDockerTestSuite {
  private val reporter               = miner11Account
  private val idleMiner              = miner21Account
  private val idleMinerRewardAddress = miner21RewardAddress

  "Another miner starts mining in the same epoch, in which the previous miner has been evicted" in {
    // Set maxSkippedEpochCount (to speed up the test)
    val maxSkippedEpochCount = 2
    waves1.api.broadcastAndWait(
      TxHelpers.dataEntry(chainContractAccount, IntegerDataEntry("maxSkippedEpochCount", maxSkippedEpochCount))
    )

    val heightBeforeJoin = waves1.api.height()
    waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = idleMiner,
        elRewardAddress = idleMinerRewardAddress
      )
    )
    // Assertion: idle miner has successfully joined
    waves1.api.dataByKey(chainContractAddress, "allMiners").value shouldBe
      StringDataEntry("allMiners", s"${idleMiner.toAddress},${reporter.toAddress}")

    val lastReportedHeight = Range
      .inclusive(1, maxSkippedEpochCount)
      .foldLeft(Height(0))((prevReportedHeight, _) => {

        // Wait for another idle miner epoch
        waves1.api.waitForHeight(prevReportedHeight + 1)
        chainContract.waitForMinerEpoch(idleMiner)
        val lastWavesBlock = waves1.api.blockHeader(waves1.api.height()).value
        val vrf            = ByteStr.decodeBase58(lastWavesBlock.VRF).get
        // Report empty epoch
        val reportResult = waves1.api.broadcastAndWait(ChainContract.reportEmptyEpoch(reporter, vrf))
        reportResult.height
      })

    // Assertion: idle miner has been evicted
    waves1.api.dataByKey(chainContractAddress, "allMiners").value shouldBe StringDataEntry("allMiners", s"${reporter.toAddress}")

    // Assertion: reporter started mining on the same epoch, in which the previous miner has been evicted
    val epochMeta = eventually {
      chainContract.getEpochMeta(lastReportedHeight).value
    }
    epochMeta.miner shouldBe reporter.toAddress
  }
}
