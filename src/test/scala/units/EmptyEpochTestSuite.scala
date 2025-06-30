package units

import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.state.{IntegerDataEntry, StringDataEntry}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet

class EmptyEpochTestSuite extends BaseTestSuite {
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
      d.advanceNewBlocks(idleMiner)

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
      d.advanceNewBlocks(reporter1)

      // Claim reporter reward with wrong epoch number
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, List(42L)))

      // Assertion: a reporter reward is NOT paid
      d.portfolio(reporter1.address) shouldBe Seq.empty

      // Claim reporter reward with right epoch number
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, List(reportedEpochNumber)))

      // Assertion: only an idle miner address is left for the reported empty epoch
      d.accountsApi.data(d.chainContractAddress, epochReportedEmptyKey) shouldBe
        Some(StringDataEntry(epochReportedEmptyKey, s"${idleMiner.address}"))

      // Assertion: skipped epoch count remains in the state
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 1L))

      // Assertion: a reporter reward is paid
      d.portfolio(reporter1.address) shouldBe Seq((d.nativeTokenId, emptyEpochReportReward))
    }
  }

  "Empty epoch confirmed 2 times, reporter rewarded 2 times, skipped epoch count is 2" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Start idleMiner
      d.advanceNewBlocks(idleMiner)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Assertion: skipped epoch count for miner is in state
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      // Assertion: a reporter reward is not paid yet
      d.portfolio(reporter1.address) shouldBe Seq.empty

      // Start reporter1
      d.advanceNewBlocks(reporter1)

      // Claim reporter reward
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, List(3L, 4L)))

      // Assertion: skipped epoch count remains in the state
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      // Assertion: a reporter reward is paid
      d.portfolio(reporter1.address) shouldBe Seq((d.nativeTokenId, 2 * emptyEpochReportReward))
    }
  }

  "Empty epoch can be reported only once" in {
    val settings = defaultSettings.copy(initialMiners = List(idleMiner, reporter1, reporter2))
    withExtensionDomain(settings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner)

      // Report empty epoch, 1st time
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Report empty epoch, 2nd time
      val reportResult2 = d.appendMicroBlockE(d.ChainContract.reportEmptyEpoch(reporter1.account))
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
      d.advanceNewBlocks(reporter1)

      // Claim reporter reward
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, List(4L)))

      // Assertion: first reporter is rewarded only once
      d.portfolio(reporter1.address) shouldBe Seq((d.nativeTokenId, emptyEpochReportReward))

      // Assertion: second reporter is not rewarded at all
      d.portfolio(reporter2.address) shouldBe Seq.empty
    }
  }

  "A reward is only paid when claimed by the reporter, once for epoch" in {
    val settings = defaultSettings.copy(initialMiners = List(idleMiner, reporter1, reporter2))
    withExtensionDomain(settings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Start reporter1
      d.advanceNewBlocks(reporter1)

      // Claim reporter reward from irrelevant account
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter2.account, List(4L)))

      // Assertion: reporter is not rewarded yet
      d.portfolio(reporter1.address) shouldBe Seq.empty

      // Claim reporter reward
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, List(4L)))

      // Assertion: reporter is rewarded
      d.portfolio(reporter1.address) shouldBe Seq((d.nativeTokenId, emptyEpochReportReward))

      // Claim reporter reward again (should not be rewarded twice)
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, List(4L)))

      // Assertion: reporter is rewarded only once, balance not changed
      d.portfolio(reporter1.address) shouldBe Seq((d.nativeTokenId, emptyEpochReportReward))
    }
  }

  "Claim with list of more than 100 epochs is rejected" in {
    withExtensionDomain(defaultSettings) { d =>
      // Build a list of 100 epochs
      val epochList1 = Range.inclusive(1, 100).map(_.toLong)

      // Assertion: claim is successful
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, epochList1))

      // Build a list of 101 epoch
      val epochList2 = Range.inclusive(1, 101).map(_.toLong)

      // Assertion: claim failed
      d.appendMicroBlockE(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, epochList2)) should matchPattern {
        case Left(err) if err.toString.contains("Claimed epochs count exceeds 100") =>
      }
    }
  }

  "The last epoch in a list of 100 is claimed successfully" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Start reporter1
      d.advanceNewBlocks(reporter1)

      // Build a list of 100 epochs, with the last one being the reported one
      val epochList = Range.inclusive(1001, 1099).map(_.toLong) ++ Seq(3L)
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, epochList))

      // Assertion: reporter is rewarded
      d.portfolio(reporter1.address) shouldBe Seq((d.nativeTokenId, emptyEpochReportReward))
    }
  }

  "Empty epoch changed to non-empty after reporting, a reporter is not rewarded" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner)

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
      d.advanceNewBlocks(reporter1)

      // Claim reporter reward
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, List(reportedEpochNumber)))

      // Assertion: a reporter is not rewarded
      d.portfolio(reporter1.address) shouldBe Seq.empty
    }
  }

  "Non-empty epoch reported, report rejected" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner)

      // Append a block
      val ecBlock1 = d.createEcBlockBuilder("0", idleMiner).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(idleMiner.account, ecBlock1))

      // Report empty epoch
      val reportResult = d.appendMicroBlockE(d.ChainContract.reportEmptyEpoch(reporter1.account))
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
      d.advanceNewBlocks(reporter1)

      // Claim reporter reward
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, List(reportedEpochNumber)))

      // Assertion: a reporter is not rewarded
      d.portfolio(reporter1.address) shouldBe Seq.empty
    }
  }

  "Miner started mining after 2 epochs, but skipped epoch count is preserved for the future measures" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Start idleMiner again
      d.advanceNewBlocks(idleMiner)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Start idleMiner again
      d.advanceNewBlocks(idleMiner)

      // Append a block
      val ecBlock1 = d.createEcBlockBuilder("0", idleMiner).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(idleMiner.account, ecBlock1))

      // Assertion: skipped epoch count for miner is in state
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      // Start reporter1
      d.advanceNewBlocks(reporter1)

      // Claim reporter reward
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, List(3L, 4L)))

      // Assertion: skipped epoch count remains in the state
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe
        Some(IntegerDataEntry(minerSkippedEpochCountKey, 2L))

      // Assertion: a reporter reward is paid
      d.portfolio(reporter1.address) shouldBe Seq((d.nativeTokenId, 2 * emptyEpochReportReward))
    }
  }

  "Reporter can not have their reward until an epoch is completed" in {
    withExtensionDomain(defaultSettings) { d =>
      // Start idleMiner
      d.advanceNewBlocks(idleMiner)

      // Report empty epoch
      d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

      // Claim reporter reward
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, List(3L)))

      // Assertion: a reporter is not rewarded, because the epoch is not completed
      d.portfolio(reporter1.address) shouldBe Seq.empty

      // Start reporter1
      d.advanceNewBlocks(reporter1)

      // Claim reporter reward for the same epoch
      d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(reporter1.account, List(3L)))

      // Assertion: a reporter is rewarded
      d.portfolio(reporter1.address) shouldBe Seq((d.nativeTokenId, emptyEpochReportReward))
    }
  }

  "Idle miner is immediately evicted on reaching MAX_SKIPPED_EPOCH_COUNT and another miner can continue mining" in {
    val thisMiner = ElMinerSettings(Wallet.generateNewAccount(super.defaultSettings.walletSeed, 0))
    val settings  = defaultSettings.copy(initialMiners = List(thisMiner, idleMiner))
    withExtensionDomain(settings) { d =>
      // This test checks that claiming by 100 epochs at once works fine
      val maxSkippedEpochCount = 200
      var reportedEpochs       = List.empty[Int]

      // Set maxSkippedEpochCount (to speed up the test)
      d.appendBlock(TxHelpers.dataEntry(d.chainContractAccount, IntegerDataEntry("maxSkippedEpochCount", maxSkippedEpochCount)))

      Range
        .inclusive(1, maxSkippedEpochCount)
        .foreach(_ => {
          // Start new epoch for idleMiner
          d.advanceNewBlocks(idleMiner)

          // Remember reported epochs
          reportedEpochs = d.blockchain.height :: reportedEpochs

          // Report empty epoch
          d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(thisMiner.account))
        })

      // Assertion: allMiners does not contain idleMiner
      d.accountsApi.data(d.chainContractAddress, "allMiners") shouldBe
        Some(StringDataEntry("allMiners", s"${thisMiner.address}"))

      // Assertion: skipped epoch count for evicted miner is reset
      val minerSkippedEpochCountKey = s"miner_${idleMiner.address}_SkippedEpochCount"
      d.accountsApi.data(d.chainContractAddress, minerSkippedEpochCountKey) shouldBe None

      // Assertion: an evicted miner can no longer call extendMainChain, and another miner is expected to mine immediately
      val ecBlock1 = d.createEcBlockBuilder("0", idleMiner).build()
      d.appendMicroBlockE(d.ChainContract.extendMainChain(idleMiner.account, ecBlock1)) should matchPattern {
        case Left(err)
            if err.toString.contains(s"${idleMiner.address} is not allowed to mine in ${d.blockchain.height} epoch. Expected ${thisMiner.address}") =>
      }

      // Assertion: Another miner can mine immediately
      // Note: can mine ≠ started mining. The actual start is checked by EmptyEpochMinerEvictionTestSuite.
      val ecBlock2 = d.createEcBlockBuilder("0", thisMiner).build()
      d.appendMicroBlock(d.ChainContract.extendMainChain(thisMiner.account, ecBlock2))

      // Claim reporter reward
      val emptyEpochChunks = Range.inclusive(1, d.blockchain.height).map(_.toLong).grouped(maxEpochsPerClaim)
      emptyEpochChunks.foreach { emptyEpochList =>
        // Start new epoch
        d.advanceNewBlocks(thisMiner)
        d.appendMicroBlock(d.ChainContract.claimEmptyEpochReportRewards(thisMiner.account, emptyEpochList))
      }

      // Assertion: a reporter is rewarded for all skipped epochs, including the last one
      // Also, this assertion checks that claiming by 100 epochs at once works fine
      d.portfolio(thisMiner.address) shouldBe Seq((d.nativeTokenId, maxSkippedEpochCount * emptyEpochReportReward))
    }
  }

  "Even when 2 idle miners are ready to be evicted in one epoch, an epoch still can be reported only once" in {
    val idleMiner2 = ElMinerSettings(TxHelpers.signer(2))
    val reporter1  = ElMinerSettings(TxHelpers.signer(3))
    val settings   = defaultSettings.copy(initialMiners = List(idleMiner, idleMiner2, reporter1))
    withExtensionDomain(settings) { d =>
      val maxSkippedEpochCount = 10
      var reportedEpochs1      = List.empty[Int]
      var reportedEpochs2      = List.empty[Int]

      // Set maxSkippedEpochCount (to speed up the test)
      d.appendBlock(TxHelpers.dataEntry(d.chainContractAccount, IntegerDataEntry("maxSkippedEpochCount", maxSkippedEpochCount)))

      Range
        .inclusive(1, maxSkippedEpochCount - 1)
        .foreach(_ => {
          // Start new epoch for idleMiner
          d.advanceNewBlocks(idleMiner)

          // Remember reported epochs
          reportedEpochs1 = d.blockchain.height :: reportedEpochs1

          // Report empty epoch
          d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))

          // Start new epoch for idleMiner2
          d.advanceNewBlocks(idleMiner2)

          // Remember reported epochs
          reportedEpochs2 = d.blockchain.height :: reportedEpochs2

          // Report empty epoch
          d.appendMicroBlock(d.ChainContract.reportEmptyEpoch(reporter1.account))
        })

      // Start new epoch for idleMiner
      d.advanceNewBlocks(idleMiner)

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
      val reportRes = d.appendMicroBlockE(d.ChainContract.reportEmptyEpoch(reporter1.account))
      // Even when 2 idle miners are ready to be evicted in one epoch, an epoch still can be reported only once
      reportRes should matchPattern {
        case Left(err) if err.toString.contains("Current epoch is already reported to be empty") =>
      }
    }
  }
}
