package units

import com.wavesplatform.account.Address
import com.wavesplatform.state.{Height, IntegerDataEntry, StringDataEntry}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.transaction.TxHelpers

import scala.concurrent.duration.*
import org.scalatest.concurrent.PatienceConfiguration.*

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

    waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = idleMiner,
        elRewardAddress = idleMinerRewardAddress
      )
    )
    // Assertion: idle miner has successfully joined
    waves1.api.dataByKey(chainContractAddress, "allMiners").value shouldBe
      StringDataEntry("allMiners", s"${idleMiner.toAddress},${reporter.toAddress}")

    val lastReportedHeight = eventually(Timeout(1.minute), Interval(2.seconds)) {
      chainContract.waitForMinerEpoch(idleMiner)

      val lastWavesBlock = waves1.api.blockHeader(waves1.api.height()).value

      val vrf = ByteStr.decodeBase58(lastWavesBlock.VRF).get
      // Report empty epoch
      val h = waves1.api.broadcastAndWait(ChainContract.reportEmptyEpoch(reporter, vrf)).height
      // Assertion: idle miner has been evicted
      waves1.api.dataByKey(chainContractAddress, "allMiners").value shouldBe StringDataEntry("allMiners", s"${reporter.toAddress}")
      h
    }

    // Assertion: reporter started mining on the same epoch, in which the previous miner has been evicted
    val epochMeta = eventually {
      chainContract.getEpochMeta(lastReportedHeight).value
    }
    epochMeta.miner shouldBe reporter.toAddress
  }
}
