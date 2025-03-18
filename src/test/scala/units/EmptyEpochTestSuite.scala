package units

import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.state.{IntegerDataEntry, StringDataEntry}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.lang.v1.compiler.Terms.{ARR, CONST_LONG}
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import units.ELUpdater.ClChangedProcessingDelay

class EmptyEpochTestSuite extends BaseIntegrationTestSuite {
  private val idleMiner = ElMinerSettings(TxHelpers.signer(1))
  private val reporter1 = ElMinerSettings(TxHelpers.signer(2))
  private val reporter2 = ElMinerSettings(TxHelpers.signer(3))

  val emptyEpochReportReward = 50_000_000L

  // It's important to match the value in the contract.
  // This way the test proves that it's possible to claim rewards for this many epochs and not hit any of limits.
  val maxEpochsPerClaim = 100

  override protected val defaultSettings: TestSettings =
    super.defaultSettings.copy(initialMiners = List(idleMiner, reporter1)).withEnabledElMining

  "Empty epoch confirmed, reporter rewarded" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Assertion: epoch is reported empty in state
      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"empty_$reportedEpochNumber%08d"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe
        Some(StringDataEntry(epochReportedEmptyKey, s"${idleMiner.address},${reporter1.address}"))

      // Assertion: skipped epoch count for miner is in state
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      // Assertion: a reporter reward is not paid yet
      d.portfolio(reporter1.address) shouldBe Seq.empty

      // Start reporter1
      d.advanceNewBlocks(reporter1.address)

      // Claim reporter reward with wrong epoch number
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(42L)), limited = true).explicitGet()),
          invoker = reporter1.account
        )
      )

      // Assertion: a reporter reward is NOT paid
      d.portfolio(reporter1.address) shouldBe Seq.empty

      // Claim reporter reward with right epoch number
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(reportedEpochNumber)), limited = true).explicitGet()),
          invoker = reporter1.account
        )
      )

      // Assertion: only an idle miner address is left for the reported empty epoch
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe
        Some(StringDataEntry(epochReportedEmptyKey, s"${idleMiner.address}"))

      // Assertion: skipped epoch count remains in the state
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      // Assertion: a reporter reward is paid
      d.portfolio(reporter1.address) shouldBe
        Seq((d.token, emptyEpochReportReward))
    }
  }

  "Empty epoch confirmed 2 times, reporter rewarded 2 times, skipped epoch count is 2" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

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
          invoker = reporter1.account
        )
      )

      // Assertion: skipped epoch count remains in the state
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      // Assertion: a reporter reward is paid
      d.portfolio(reporter1.address) shouldBe Seq((d.token, 2 * emptyEpochReportReward))
    }
  }

  "Empty epoch can be reported only once" in {
    val settings = defaultSettings.copy(initialMiners = List(idleMiner, reporter1, reporter2))
    withExtensionDomain(settings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch, 1st time
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Report empty epoch, 2nd time
      val reportResult2 = d.appendMicroBlockE( d.ChainContract.reportEmptyEpoch(reporter1.account) )
      reportResult2 should matchPattern {
        case Left(err) if err.toString.contains("Current epoch is already reported to be empty") =>
      }

      // Report empty epoch, 3rd time
      val reportResult3 = d.appendMicroBlockE(
        d.ChainContract.reportEmptyEpoch(reporter2.account)
      )
      reportResult3 should matchPattern {
        case Left(err) if err.toString.contains("Current epoch is already reported to be empty") =>
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
          invoker = reporter1.account
        )
      )

      // Assertion: first reporter is rewarded only once
      d.portfolio(reporter1.address) shouldBe Seq((d.token, emptyEpochReportReward))

      // Assertion: second reporter is not rewarded at all
      d.portfolio(reporter2.address) shouldBe Seq.empty
    }
  }

  "A reward is only paid when claimed by the reporter, once for epoch" in {
    val settings = defaultSettings.copy(initialMiners = List(idleMiner, reporter1, reporter2))
    withExtensionDomain(settings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Start reporter1
      d.advanceNewBlocks(reporter1.address)

      // Claim reporter reward from irrelevant account
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(4L)), limited = true).explicitGet()),
          invoker = reporter2.account
        )
      )

      // Assertion: reporter is not rewarded yet
      d.portfolio(reporter1.address) shouldBe Seq.empty

      // Claim reporter reward
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(4L)), limited = true).explicitGet()),
          invoker = reporter1.account
        )
      )

      // Assertion: reporter is rewarded
      d.portfolio(reporter1.address) shouldBe Seq((d.token, emptyEpochReportReward))

      // Claim reporter reward again (should not be rewarded twice)
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(4L)), limited = true).explicitGet()),
          invoker = reporter1.account
        )
      )

      // Assertion: reporter is rewarded only once, balance not changed
      d.portfolio(reporter1.address) shouldBe Seq((d.token, emptyEpochReportReward))
    }
  }

  "Claim with list of more than 100 epochs is rejected" in {
    withExtensionDomain(defaultSettings) { d =>
      // Build a list of 100 epochs
      val epochList1 = Range.inclusive(1, 100).map(i => CONST_LONG(i.toLong))

      // Assertion: claim is successful
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(epochList1, limited = true).explicitGet()),
          invoker = reporter1.account
        )
      )
      // Build a list of 101 epoch
      val epochList2 = Range.inclusive(1, 101).map(i => CONST_LONG(i.toLong))

      // Assertion: claim failed
      d.appendMicroBlockE(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(epochList2, limited = true).explicitGet()),
          invoker = reporter1.account
        )
      ) should matchPattern {
        case Left(err) if err.toString.contains("Claimed epochs count exceeds 100") =>
      }
    }
  }

  "The last epoch in a list of 100 is claimed successfully" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Start reporter1
      d.advanceNewBlocks(reporter1.address)

      // Build a list of 100 epochs, with the last one being the reported one
      val epochList = Range.inclusive(1001, 1099).map(i => CONST_LONG(i.toLong)) ++ Seq(CONST_LONG(3L))
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(epochList, limited = true).explicitGet()),
          invoker = reporter1.account
        )
      )

      // Assertion: reporter is rewarded
      d.portfolio(reporter1.address) shouldBe Seq((d.token, emptyEpochReportReward))
    }
  }

  "Empty epoch changed to non-empty after reporting, a reporter is not rewarded" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Assertion: epoch is reported empty in state
      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"empty_$reportedEpochNumber%08d"
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe
        Some(StringDataEntry(epochReportedEmptyKey, s"${idleMiner.address},${reporter1.address}"))

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
          invoker = reporter1.account
        )
      )

      // Assertion: a reporter is not rewarded
      d.portfolio(reporter1.address) shouldBe Seq.empty
    }
  }

  "Non-empty epoch reported, report rejected" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Append a block
      val ecBlock1 = d.createEcBlockBuilder("0", idleMiner).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(idleMiner.account, ecBlock1))

      // Report empty epoch
      val reportResult = d.appendMicroBlockE( d.ChainContract.reportEmptyEpoch(reporter1.account) )
      reportResult should matchPattern {
        case Left(err) if err.toString.contains("Current epoch is non-empty") =>
      }

      // Assertion: an epoch is not marked empty
      val reportedEpochNumber   = 3
      val epochReportedEmptyKey = f"empty_$reportedEpochNumber%08d"
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
          invoker = reporter1.account
        )
      )

      // Assertion: a reporter is not rewarded
      d.portfolio(reporter1.address) shouldBe Seq.empty
    }
  }

  "Miner started mining after 2 epochs, but skipped epoch count is preserved for the future measures" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Start idleMiner again
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Start idleMiner again
      d.advanceNewBlocks(idleMiner.address)

      // Append a block
      val ecBlock1 = d.createEcBlockBuilder("0", idleMiner).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(idleMiner.account, ecBlock1))

      // Assertion: skipped epoch count for miner is in state
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      // Start reporter1
      d.advanceNewBlocks(reporter1.address)

      // Claim reporter reward
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(3L), CONST_LONG(4L)), limited = true).explicitGet()),
          invoker = reporter1.account
        )
      )

      // Assertion: skipped epoch count remains in the state
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      // Assertion: a reporter reward is paid
      d.portfolio(reporter1.address) shouldBe Seq((d.token, 2 * emptyEpochReportReward))
    }
  }

  "Reporter can not have their reward until an epoch is completed" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Claim reporter reward
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(3L)), limited = true).explicitGet()),
          invoker = reporter1.account
        )
      )

      // Assertion: a reporter is not rewarded, because the epoch is not completed
      d.portfolio(reporter1.address) shouldBe Seq.empty

      // Start reporter1
      d.advanceNewBlocks(reporter1.address)

      // Claim reporter reward for the same epoch
      d.appendMicroBlock(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("claimEmptyEpochReportRewards"),
          List(ARR(Vector(CONST_LONG(3L)), limited = true).explicitGet()),
          invoker = reporter1.account
        )
      )

      // Assertion: a reporter is rewarded
      d.portfolio(reporter1.address) shouldBe Seq((d.token, emptyEpochReportReward))
    }
  }

  "Idle miner is immediately evicted on reaching MAX_SKIPPED_EPOCH_COUNT and another miner can continue mining" in {
    withExtensionDomain(defaultSettings) { d =>
      val maxSkippedEpochCount = 200
      var reportedEpochs       = List.empty[Int]

      Range
        .inclusive(1, maxSkippedEpochCount)
        .foreach(_ => {
          // Start new epoch for idleMiner
          d.advanceNewBlocks(idleMiner.address)

          // Remember reported epochs
          reportedEpochs = d.blockchain.height :: reportedEpochs

          // Report empty epoch
          d.appendMicroBlock( d.ChainContract.reportEmptyEpoch(reporter1.account) )
        })

      // Wait for handleConsensusLayerChanged to be executed in order for reporter1 to start mining
      d.advanceElu(ClChangedProcessingDelay)

      // Assertion: skipped epoch count for evicted miner is reset
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe None

      // Assertion: an evicted miner can no longer call extendMainChain, and another miner is expected to mine immediately
      val ecBlock1 = d.createEcBlockBuilder("0", idleMiner).build()
      d.appendMicroBlockE(d.ChainContract.extendMainChain(idleMiner.account, ecBlock1)) should matchPattern {
        case Left(err)
            if err.toString.contains(s"${idleMiner.address} is not allowed to mine in ${d.blockchain.height} epoch. Expected ${reporter1.address}") =>
      }

      // Assertion: Another miner actually can mine immediately
      val ecBlock2 = d.createEcBlockBuilder("0", reporter1).build()
      d.appendMicroBlock(d.ChainContract.extendMainChain(reporter1.account, ecBlock2))

      // Claim reporter reward
      val emptyEpochChunks = Range.inclusive(1, d.blockchain.height).map(i => CONST_LONG(i.toLong)).grouped(maxEpochsPerClaim)
      emptyEpochChunks.foreach { emptyEpochList =>
        // Start new epoch
        d.advanceNewBlocks(reporter1.address)
        d.appendMicroBlock(
          TxHelpers.invoke(
            d.chainContractAddress,
            Some("claimEmptyEpochReportRewards"),
            List(ARR(emptyEpochList, limited = true).explicitGet()),
            invoker = reporter1.account
          )
        )
      }

      // Assertion: a reporter is rewarded for all skipped epochs, including the last one
      d.portfolio(reporter1.address) shouldBe Seq((d.token, maxSkippedEpochCount * emptyEpochReportReward))
    }
  }

  "Even when 2 idle miners are ready to be evicted in one epoch, an epoch still can be reported only once" in {
    val idleMiner2 = ElMinerSettings(TxHelpers.signer(2))
    val reporter1  = ElMinerSettings(TxHelpers.signer(5))
    val settings   = defaultSettings.copy(initialMiners = List(idleMiner, idleMiner2, reporter1))
    withExtensionDomain(settings) { d =>
      val maxSkippedEpochCount = 200
      var reportedEpochs1      = List.empty[Int]
      var reportedEpochs2      = List.empty[Int]

      Range
        .inclusive(1, maxSkippedEpochCount - 1)
        .foreach(_ => {
          // Start new epoch for idleMiner
          d.advanceNewBlocks(idleMiner.address)

          // Remember reported epochs
          reportedEpochs1 = d.blockchain.height :: reportedEpochs1

          // Report empty epoch
          d.appendMicroBlock( d.ChainContract.reportEmptyEpoch(reporter1.account) )

          // Start new epoch for idleMiner2
          d.advanceNewBlocks(idleMiner2.address)

          // Remember reported epochs
          reportedEpochs2 = d.blockchain.height :: reportedEpochs2

          // Report empty epoch
          d.appendMicroBlock( d.ChainContract.reportEmptyEpoch(reporter1.account) )
        })

      // Start new epoch for idleMiner
      d.advanceNewBlocks(idleMiner.address)

      // Remember reported epochs
      reportedEpochs1 = d.blockchain.height :: reportedEpochs1

      // Report empty epoch for idleMiner
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Assertion: skipped epoch count for evicted miner is reset
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe None

      // Assertion: an evicted miner can no longer call extendMainChain, and idleMiner2 is expected to mine immediately
      val ecBlock1 = d.createEcBlockBuilder("0", idleMiner).build()
      d.appendMicroBlockE(d.ChainContract.extendMainChain(idleMiner.account, ecBlock1)) should matchPattern {
        case Left(err)
            if err.toString.contains(
              s"${idleMiner.address} is not allowed to mine in ${d.blockchain.height} epoch. Expected ${idleMiner2.address}"
            ) =>
      }

      // Assertion: idleMiner2 is ready to be evicted too (1 report from being evicted)
      val minerSkippedEpochCountKey2 = s"miner_${idleMiner2.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey2) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey2, maxSkippedEpochCount - 1))

      // Report empty epoch for idleMiner2
      val reportRes = d.appendMicroBlockE( d.ChainContract.reportEmptyEpoch(reporter1.account) )
      // Even when 2 idle miners are ready to be evicted in one epoch, an epoch still can be reported only once
      reportRes should matchPattern {
        case Left(err) if err.toString.contains("Current epoch is already reported to be empty") =>
      }
    }
  }
}
