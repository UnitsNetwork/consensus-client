package units

import com.wavesplatform.account.Address
import com.wavesplatform.state.{Height, IntegerDataEntry, StringDataEntry}
import com.wavesplatform.transaction.TxHelpers
import units.el.ElBridgeClient
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.utils.Convert
import scala.jdk.OptionConverters._

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
    waves1.api.dataByKey(chainContractAddress, "allMiners") shouldBe Some(
      StringDataEntry("allMiners", s"${reporter.toAddress},${idleMiner.toAddress}")
    )

    val lastReportedHeight = Range
      .inclusive(1, maxSkippedEpochCount)
      .foldLeft(Height(0))((prevReportedHeight, _) => {

        // Wait for another idle miner epoch
        waves1.api.waitForHeight(prevReportedHeight + 1)
        chainContract.waitForMinerEpoch(idleMiner)

        // Report empty epoch
        val reportResult = waves1.api.broadcastAndWait(ChainContract.reportEmptyEpoch(reporter))
        reportResult.height
      })

    // Assertion: idle miner has been evicted
    waves1.api.dataByKey(chainContractAddress, "allMiners") shouldBe Some(StringDataEntry("allMiners", s"${reporter.toAddress}"))

    // Send an L2 transaction
    val tenGwei        = BigInt(Convert.toWei("10", Convert.Unit.GWEI).toBigIntegerExact)
    val transferResult = elBridge.sendSendNative(elRichAccount1, clRichAccount1.toAddress, tenGwei)

    // Assertion: L2 transaction is successful
    eventually {
      val r = ec1.web3j.ethGetTransactionReceipt(transferResult.getTransactionHash).send().getTransactionReceipt.toScala.value
      if (!r.isStatusOK) fail(s"Expected successful sendNative, got: ${ElBridgeClient.decodeRevertReason(r.getRevertReason)}")
      r
    }

    // Assertion: reporter started mining exactly on lastReportedHeight
    waves1.api.height() shouldBe lastReportedHeight
  }
}
