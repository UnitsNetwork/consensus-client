package units

import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.state.{IntegerDataEntry, StringDataEntry}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.lang.v1.compiler.Terms.{ARR, CONST_LONG}
import com.wavesplatform.common.utils.EitherExt2.explicitGet

class EmptyEpochTestSuite extends BaseIntegrationTestSuite {
  private val idleMiner = ElMinerSettings(TxHelpers.signer(1))
  private val reporter1 = ElMinerSettings(TxHelpers.signer(2))
  private val reporter2 = ElMinerSettings(TxHelpers.signer(3))

  override protected val defaultSettings: TestSettings =
    super.defaultSettings.copy(initialMiners = List(idleMiner, reporter1)).withEnabledElMining

  "Empty epoch confirmed, reporter rewarded" in {
    val settings = defaultSettings
    withExtensionDomain(settings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Assertion: epoch is reported empty in state
      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"epoch_$reportedEpochNumber%08d_ReportedEmpty"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe
        Some(StringDataEntry(epochReportedEmptyKey, s"${reporter1.address}"))

      // Assertion: skipped epoch count for miner is in state
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      // Assertion: a reporter reward is not paid yet
      d.portfolio(reporter1.address) shouldBe Seq.empty

      // Start reporter1
      d.advanceNewBlocks(reporter1.address)

      // Claim reporter reward
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(reportedEpochNumber)), limited = true).explicitGet()),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Assertion: epoch is no longer reported empty in state
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe None

      // Assertion: skipped epoch count remains in the state
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      // Assertion: a reporter reward is paid
      d.portfolio(reporter1.address) shouldBe
        Seq((d.token, 50_000_000L))
    }
  }

  "Empty epoch confirmed 2 times, reporter rewarded 2 times, skipped epoch count is 2" in {
    val settings = defaultSettings
    withExtensionDomain(settings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Start reporter1
      d.advanceNewBlocks(reporter1.address)

      // Claim reporter reward
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(3L), CONST_LONG(4L)), limited = true).explicitGet()),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Assertion: skipped epoch count remains in the state
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      // Assertion: a reporter reward is paid
      d.portfolio(reporter1.address) shouldBe Seq((d.token, 100_000_000L))
    }
  }

  "Empty epoch can be reported only once" in {
    val settings = defaultSettings.copy(initialMiners = List(idleMiner, reporter1, reporter2)).withEnabledElMining
    withExtensionDomain(settings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch, 1st time
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Report empty epoch, 2nd time
      val reportResult2 = d.appendMicroBlockE(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          payments = Seq(),
          invoker = reporter1.account
        )
      )
      reportResult2 should matchPattern {
        case Left(err) if err.toString.contains("Current epoch is already reported to be empty.") =>
      }

      // Report empty epoch, 3rd time
      val reportResult3 = d.appendMicroBlockE(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          payments = Seq(),
          invoker = reporter2.account
        )
      )
      reportResult3 should matchPattern {
        case Left(err) if err.toString.contains("Current epoch is already reported to be empty.") =>
      }

      // Assertion: miner skipped epoch count increased only once
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      // Start reporter1
      d.advanceNewBlocks(reporter1.address)

      // Claim reporter reward
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(4L)), limited = true).explicitGet()),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Assertion: first reporter is rewarded only once
      d.portfolio(reporter1.address) shouldBe Seq((d.token, 50_000_000L))

      // Assertion: second reporter is not rewarded at all
      d.portfolio(reporter2.address) shouldBe Seq.empty
    }
  }

  "Empty epoch changed to non-empty after reporting" in {
    val settings = defaultSettings
    withExtensionDomain(settings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Assertion: epoch is reported empty in state
      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"epoch_$reportedEpochNumber%08d_ReportedEmpty"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe
        Some(StringDataEntry(epochReportedEmptyKey, s"${reporter1.address}"))

      // Assertion: skipped epoch count for miner is in state
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      // Append a block
      val ecBlock1 = d.createEcBlockBuilder("0", idleMiner).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(idleMiner.account, ecBlock1))

      // Assertion: an epoch is not marked empty anymore
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe None

      // Assertion: miner has their skipped epoch count reset
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe None

      // Start reporter1
      d.advanceNewBlocks(reporter1.address)

      // Claim reporter reward
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(reportedEpochNumber)), limited = true).explicitGet()),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Assertion: a reporter is not rewarded
      d.portfolio(reporter1.address) shouldBe Seq.empty
    }
  }

  "Non-empty epoch reported, report rejected" in {
    val settings = defaultSettings
    withExtensionDomain(settings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Append a block
      val ecBlock1 = d.createEcBlockBuilder("0", idleMiner).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(idleMiner.account, ecBlock1))

      // Report empty epoch
      val reportResult = d.appendMicroBlockE(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          payments = Seq(),
          invoker = reporter1.account
        )
      )
      reportResult should matchPattern {
        case Left(err) if err.toString.contains("Current epoch is non-empty.") =>
      }

      // Assertion: an epoch is not marked empty
      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"epoch_$reportedEpochNumber%08d_ReportedEmpty"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe None

      // Assertion: skipped epoch count is not set
      val minerSkippedEpochKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochKey) shouldBe None

      // Start reporter1
      d.advanceNewBlocks(reporter1.address)

      // Claim reporter reward
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(reportedEpochNumber)), limited = true).explicitGet()),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Assertion: a reporter is not rewarded
      d.portfolio(reporter1.address) shouldBe Seq.empty
    }
  }

  "2 Empty epochs confirmed, reporter rewarded twice" in {
    val settings = defaultSettings
    withExtensionDomain(settings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Start idleMiner again
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Assertion: skipped epoch count for miner is in state
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      // Assertion: a reporter reward is not paid yet
      d.portfolio(reporter1.address) shouldBe Seq.empty

      // Start reporter1
      d.advanceNewBlocks(reporter1.address)

      // Claim reporter reward
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(3L), CONST_LONG(4L)), limited = true).explicitGet()),
          payments = Seq(),
          invoker = reporter1.account
        )
      )

      // Assertion: skipped epoch count remains in the state
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      // Assertion: a reporter reward is paid
      d.portfolio(reporter1.address) shouldBe
        Seq((d.token, 100_000_000L))
    }
  }
}
