package units

import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.state.{IntegerDataEntry, StringDataEntry}
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

      // End miner1
      d.advanceConsensusLayerChanged()

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("0", miner2).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      val minerSkippedEpochCountKey = s"miner_${miner1.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"epoch_$reportedEpochNumber%08d_ReportedEmpty"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe Some(
        StringDataEntry(epochReportedEmptyKey, s"${miner2.address}")
      )

      // TODO: assert balance (miner2 should get a reward)
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

      // End miner1
      d.advanceConsensusLayerChanged()

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

      // End miner1
      d.advanceConsensusLayerChanged()

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("0", miner2).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      val minerSkippedEpochCountKey = s"miner_${miner1.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"epoch_$reportedEpochNumber%08d_ReportedEmpty"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe Some(
        StringDataEntry(epochReportedEmptyKey, s"${miner2.address}")
      )

      // TODO: assert balance (miner2 should get a reward)
    }
  }

  "Empty epoch can be reported only once" in {
    val settings = defaultSettings.copy(initialMiners = List(miner1, miner2, miner3)).withEnabledElMining
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

      // Report empty epoch
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

      // Report empty epoch
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

      // End miner1
      d.advanceConsensusLayerChanged()

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("0", miner2).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      val minerSkippedEpochCountKey = s"miner_${miner1.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      val reportedEpochNumber   = 4
      val epochReportedEmptyKey = f"epoch_$reportedEpochNumber%08d_ReportedEmpty"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe Some(
        StringDataEntry(epochReportedEmptyKey, s"${miner2.address}")
      )
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

      // Append a block
      val ecBlock1 = d.createEcBlockBuilder("0", miner1).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner1.account, ecBlock1))

      // End miner1
      d.advanceConsensusLayerChanged()

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("0", miner2, ecBlock1).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      val minerSkippedEpochKey = s"miner_${miner1.address}_SkippedEpochCount"
       d.accountsApi.data(d.chainContractAddress, minerSkippedEpochKey) shouldBe None

      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"epoch_$reportedEpochNumber%08d_ReportedEmpty"
       d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe None

      // TODO: assert balance (miner2 should NOT get a reward)
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

      // End miner1
      d.advanceConsensusLayerChanged()

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("0", miner2, ecBlock1).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      val minerSkippedEpochKey = s"miner_${miner1.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochKey) shouldBe None

      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"epoch_$reportedEpochNumber%08d_ReportedEmpty"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe None

      // TODO: assert balance (miner2 should NOT get a reward)
    }
  }

  // TODO: add test: reset to 0 on mining another epoch
}
