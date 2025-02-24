package units

import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.state.{IntegerDataEntry, StringDataEntry}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxHelpers

class EmptyEpochTestSuite extends BaseIntegrationTestSuite {
  private val miner1 = ElMinerSettings(TxHelpers.signer(1))
  private val miner2 = ElMinerSettings(TxHelpers.signer(2))
  private val miner3 = ElMinerSettings(TxHelpers.signer(3))

  override protected val defaultSettings: TestSettings =
    super.defaultSettings.copy(initialMiners = List(miner1, miner2)).withEnabledElMining

  "Empty epoch confirmed, reporter rewarded" in {
    val settings = defaultSettings
    withExtensionDomain(settings) { d =>
      // Start miner1
      d.advanceNewBlocks(miner1.address)

      // Report empty epoch
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          Seq.empty,
          payments = Seq(),
          invoker = miner2.account
        )
      )

      // Assertion: epoch is reported empty in state
      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"epoch_$reportedEpochNumber%08d_ReportedEmpty"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe
        Some(StringDataEntry(epochReportedEmptyKey, s"${miner2.address}"))

      // Assertion: skipped epoch count for miner is in state
      val minerSkippedEpochCountKey = s"miner_${miner1.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      // Assertion: a reporter reward is not paid yet
      d.portfolio(miner2.address) shouldBe Seq.empty

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("1", miner2).build()
      d.appendMicroBlock(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      // Assertion: epoch is no longer reported empty in state
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe None

      // Assertion: skipped epoch count remains in the state
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      // Assertion: a reporter reward is paid
      d.portfolio(miner2.address) shouldBe
        Seq((d.token, 50_000_000L))
    }
  }

  "Empty epoch confirmed 2 times, reporter rewarded 2 times, skipped epoch count is 2" in {
    val settings = defaultSettings
    withExtensionDomain(settings) { d =>
      // Start miner1
      d.advanceNewBlocks(miner1.address)

      // Report empty epoch
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          Seq.empty,
          payments = Seq(),
          invoker = miner2.account
        )
      )

      // Start miner1
      d.advanceNewBlocks(miner1.address)

      // Report empty epoch
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          Seq.empty,
          payments = Seq(),
          invoker = miner2.account
        )
      )

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("0", miner2).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      // Assertion: skipped epoch count remains in the state
      val minerSkippedEpochCountKey = s"miner_${miner1.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      // Assertion: a reporter reward is paid
      d.portfolio(miner2.address) shouldBe Seq((d.token, 100_000_000L))
    }
  }

  "Empty epoch can be reported only once" in {
    val settings = defaultSettings.copy(initialMiners = List(miner1, miner2, miner3)).withEnabledElMining
    withExtensionDomain(settings) { d =>
      // Start miner1
      d.advanceNewBlocks(miner1.address)

      // Report empty epoch, 1st time
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          Seq.empty,
          payments = Seq(),
          invoker = miner2.account
        )
      )

      // Report empty epoch, 2nd time
      val reportResult2 = d.appendMicroBlockE(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          Seq.empty,
          payments = Seq(),
          invoker = miner2.account
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
          Seq.empty,
          payments = Seq(),
          invoker = miner3.account
        )
      )
      reportResult3 should matchPattern {
        case Left(err) if err.toString.contains("Current epoch is already reported to be empty.") =>
      }

      // Assertion: miner skipped epoch count increased only once
      val minerSkippedEpochCountKey = s"miner_${miner1.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("0", miner2).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      // Assertion: first reporter is rewarded only once
      d.portfolio(miner2.address) shouldBe Seq((d.token, 50_000_000L))

      // Assertion: second reporter is not rewarded at all
      d.portfolio(miner3.address) shouldBe Seq.empty
    }
  }

  "Empty epoch changed to non-empty after reporting" in {
    val settings = defaultSettings
    withExtensionDomain(settings) { d =>
      // Start miner1
      d.advanceNewBlocks(miner1.address)

      // Report empty epoch
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          Seq.empty,
          payments = Seq(),
          invoker = miner2.account
        )
      )

      // Assertion: epoch is reported empty in state
      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"epoch_$reportedEpochNumber%08d_ReportedEmpty"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe
        Some(StringDataEntry(epochReportedEmptyKey, s"${miner2.address}"))

      // Assertion: skipped epoch count for miner is in state
      val minerSkippedEpochCountKey = s"miner_${miner1.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      // Append a block
      val ecBlock1 = d.createEcBlockBuilder("0", miner1).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner1.account, ecBlock1))

      // Assertion: an epoch is not marked empty anymore
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe None

      // Assertion: miner has their skipped epoch count reset
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe None

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("0", miner2, ecBlock1).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      // Assertion: a reporter is not rewarded
      d.portfolio(miner2.address) shouldBe Seq.empty
    }
  }

  "Non-empty epoch reported, report rejected" in {
    val settings = defaultSettings
    withExtensionDomain(settings) { d =>
      // Start miner1
      d.advanceNewBlocks(miner1.address)

      // Append a block
      val ecBlock1 = d.createEcBlockBuilder("0", miner1).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner1.account, ecBlock1))

      // Report empty epoch
      val reportResult = d.appendMicroBlockE(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          Seq.empty,
          payments = Seq(),
          invoker = miner2.account
        )
      )
      reportResult should matchPattern {
        case Left(err) if err.toString.contains("Current epoch is non-empty.") =>
      }

      // Assertion: an epoch is not marked empty
      val reportedEpochNumber = 3
      val epochReportedEmptyKey = f"epoch_$reportedEpochNumber%08d_ReportedEmpty"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe None

      // Assertion: skipped epoch count is not set
      val minerSkippedEpochKey = s"miner_${miner1.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochKey) shouldBe None

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("0", miner2, ecBlock1).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      // Assertion: a reporter is not rewarded
      d.portfolio(miner2.address) shouldBe Seq.empty
    }
  }
}
