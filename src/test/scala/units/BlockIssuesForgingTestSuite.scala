package units

import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.extensions.ExtensionDomain
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet
import units.ELUpdater.State.ChainStatus.{FollowingChain, Mining, WaitForNewChain}
import units.ELUpdater.WaitRequestedBlockTimeout
import units.client.http.model.EcBlock

import scala.concurrent.duration.DurationInt

class BlockIssuesForgingTestSuite extends BaseIntegrationTestSuite {
  private val transferReceiver = TxHelpers.secondSigner

  private val thisMiner   = ElMinerSettings(Wallet.generateNewAccount(TestSettings.Default.walletSeed, 0))
  private val otherMiner1 = ElMinerSettings(TxHelpers.signer(2))
  private val otherMiner2 = ElMinerSettings(TxHelpers.signer(3))

  override protected val defaultSettings: TestSettings = TestSettings.Default
    .copy(
      initialMiners = List(thisMiner, otherMiner1, otherMiner2),
      additionalBalances = List(AddrWithBalance(transferReceiver.toAddress, chainContract.withdrawFee))
    )
    .withEnabledElMining

  "We're on the main chain and" - {
    def test(f: (ExtensionDomain, ConsensusClient, EcBlock, Int) => Unit): Unit = withConsensusClient() { (d, c) =>
      val ecBlock1Hash = d.createBlockHash("0")
      val ecBlock2Hash = d.createBlockHash("0-0")

      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBlock1 $ecBlock1Hash")
      d.advanceNewBlocks(otherMiner1.address)
      val ecBlock1 = d.createNextEcBlock(
        hash = ecBlock1Hash,
        parent = d.ecGenesisBlock,
        minerRewardL2Address = otherMiner1.elRewardAddress
      )
      d.ecClients.setBlockLogs(ecBlock1.hash, Bridge.ElSentNativeEventTopic, Nil)
      d.ecClients.addKnown(ecBlock1)
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(otherMiner1.account, ecBlock1, d.blockchain.height))
      waitForCS[FollowingChain](d, c) { s =>
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock1.hash
      }

      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBlock2 $ecBlock2Hash")
      d.advanceNewBlocks(otherMiner1.address)
      val ecBlock2Epoch = d.blockchain.height
      val ecBlock2 = d.createNextEcBlock(
        hash = ecBlock2Hash,
        parent = ecBlock1,
        minerRewardL2Address = otherMiner1.elRewardAddress,
        withdrawals = Vector(d.createWithdrawal(0, otherMiner1.elRewardAddress))
      )
      d.ecClients.setBlockLogs(ecBlock2.hash, Bridge.ElSentNativeEventTopic, Nil)
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(otherMiner1.account, ecBlock2, ecBlock2Epoch))

      waitForCS[FollowingChain](d, c, s"Waiting ecBlock2 ${ecBlock2.hash}") { s =>
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock2.hash
        s.nextExpectedEcBlock.value shouldBe ecBlock2.hash
      }

      step(s"Start a new epoch of thisMiner ${thisMiner.address}")
      d.advanceNewBlocks(thisMiner.address)

      f(d, c, ecBlock2, ecBlock2Epoch)
    }

    "EC-block comes within timeout - then we continue forging" in test { (d, c, ecBlock2, ecBlock2Epoch) =>
      d.advanceElu(WaitRequestedBlockTimeout - 1.millis)
      waitForCS[FollowingChain](d, c, s"Still waiting ecBlock2 ${ecBlock2.hash}") { s =>
        s.nextExpectedEcBlock.value shouldBe ecBlock2.hash
      }

      step(s"Receive EC-block ${ecBlock2.hash} from network")
      d.receiveNetworkBlock(ecBlock2, otherMiner1.account, ecBlock2Epoch)
      d.triggerScheduledTasks()

      d.ecClients.willForge(
        d.createNextEcBlock(
          hash = d.createBlockHash("0-0-0"),
          parent = ecBlock2,
          minerRewardL2Address = otherMiner1.elRewardAddress,
          withdrawals = Vector(d.createWithdrawal(0, otherMiner1.elRewardAddress))
        )
      )

      waitForCS[Mining](d, c, "Continue") { s =>
        s.nodeChainInfo.isRight shouldBe true
      }
    }

    "EC-block doesn't come - then we start an alternative chain" in test { (d, c, _, _) =>
      waitForCS[WaitForNewChain](d, c, s"Switched to alternative chain") { _ => }
    }
  }

  "We're on the alternative chain and" - {
    "EC-block comes within timeout - then we continue forging" in withConsensusClient() { (d, c) =>
      val ecBlock1Hash    = d.createBlockHash("0")
      val ecBadBlock2Hash = d.createBlockHash("0-0")
      val ecBlock2Hash    = d.createBlockHash("0-1")
      val ecBlock3Hash    = d.createBlockHash("0-1-1")

      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBlock1 $ecBlock1Hash")
      d.advanceNewBlocks(otherMiner1.address)
      val ecBlock1 = d.createNextEcBlock(
        hash = ecBlock1Hash,
        parent = d.ecGenesisBlock,
        minerRewardL2Address = otherMiner1.elRewardAddress
      )
      d.ecClients.setBlockLogs(ecBlock1.hash, Bridge.ElSentNativeEventTopic, Nil)
      d.ecClients.addKnown(ecBlock1)
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(otherMiner1.account, ecBlock1, d.blockchain.height))

      waitForCS[FollowingChain](d, c) { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock1.hash
      }

      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBadBlock2 $ecBadBlock2Hash")
      d.advanceNewBlocks(otherMiner1.address)
      val ecBadBlock2Epoch = d.blockchain.height
      val ecBadBlock2 = d.createNextEcBlock(
        hash = ecBadBlock2Hash,
        parent = ecBlock1,
        minerRewardL2Address = otherMiner1.elRewardAddress,
        withdrawals = Vector(d.createWithdrawal(0, otherMiner1.elRewardAddress))
      )
      d.ecClients.setBlockLogs(ecBadBlock2.hash, Bridge.ElSentNativeEventTopic, Nil)
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(otherMiner1.account, ecBadBlock2, ecBadBlock2Epoch))

      waitForCS[FollowingChain](d, c) { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe ecBadBlock2.hash
      }

      step(s"Start a new epoch of otherMiner2 ${otherMiner2.address} with alternative chain ecBlock2 $ecBlock2Hash")
      d.advanceNewBlocks(otherMiner2.address)

      waitForCS[WaitForNewChain](d, c) { s =>
        s.chainSwitchInfo.referenceBlock.hash shouldBe ecBlock1.hash
      }

      val ecBlock2 = d.createNextEcBlock(
        hash = ecBlock2Hash,
        parent = ecBlock1,
        minerRewardL2Address = otherMiner2.elRewardAddress,
        withdrawals = Vector(d.createWithdrawal(0, otherMiner1.elRewardAddress))
      )
      d.ecClients.setBlockLogs(ecBlock2.hash, Bridge.ElSentNativeEventTopic, Nil)
      d.appendMicroBlockAndVerify(chainContract.startAltChainV3(otherMiner2.account, ecBlock2, d.blockchain.height))

      waitForCS[FollowingChain](d, c) { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock2.hash
        s.nextExpectedEcBlock.value shouldBe ecBlock2.hash
      }

      d.receiveNetworkBlock(ecBlock2, otherMiner2.account, d.blockchain.height)
      waitForCS[FollowingChain](d, c) { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock2.hash
        s.nextExpectedEcBlock shouldBe empty
      }

      step(s"Continue an alternative chain by otherMiner2 ${otherMiner2.address} with ecBlock3 $ecBlock3Hash")
      d.advanceNewBlocks(otherMiner2.address)
      val ecBlock3Epoch = d.blockchain.height
      val ecBlock3 = d.createNextEcBlock(
        hash = ecBlock3Hash,
        parent = ecBlock2,
        minerRewardL2Address = otherMiner2.elRewardAddress,
        withdrawals = Vector(d.createWithdrawal(1, otherMiner2.elRewardAddress))
      )
      d.ecClients.setBlockLogs(ecBlock3Hash, Bridge.ElSentNativeEventTopic, Nil)
      d.appendMicroBlockAndVerify(chainContract.extendAltChainV3(otherMiner2.account, 1, ecBlock3, ecBlock3Epoch))

      waitForCS[FollowingChain](d, c) { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock3.hash
        s.nextExpectedEcBlock.value shouldBe ecBlock3.hash
      }

      step(s"Start a new epoch of thisMiner ${thisMiner.address}")
      d.advanceNewBlocks(thisMiner.address)
      d.advanceConsensusLayerChanged()
      d.advanceElu(WaitRequestedBlockTimeout - 1.millis)

      waitForCS[FollowingChain](d, c, s"Waiting ecBlock3 $ecBlock3Hash") { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock3.hash
        s.nextExpectedEcBlock.value shouldBe ecBlock3.hash
      }

      step(s"Receive ecBlock3 $ecBlock3Hash")
      d.receiveNetworkBlock(ecBlock3, thisMiner.account, ecBlock3Epoch)

      d.ecClients.willForge(
        d.createNextEcBlock(
          hash = d.createBlockHash("0-1-1-1"),
          parent = ecBlock3,
          minerRewardL2Address = thisMiner.elRewardAddress,
          withdrawals = Vector(d.createWithdrawal(2, thisMiner.elRewardAddress))
        )
      )
      waitForCS[Mining](d, c) { s =>
        s.nodeChainInfo.value.isMain shouldBe false
      }
    }

    "We mined before the alternative chain before and EC-block doesn't come - then we still wait for it" in withConsensusClient() { (d, c) =>
      val ecBlock1Hash    = d.createBlockHash("0")
      val ecBadBlock2Hash = d.createBlockHash("0-0")
      val ecBlock2Hash    = d.createBlockHash("0-1")
      val ecBadBlock3Hash = d.createBlockHash("0-1-1")

      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBlock1 $ecBlock1Hash")
      d.advanceNewBlocks(otherMiner1.address)
      val ecBlock1 = d.createNextEcBlock(
        hash = ecBlock1Hash,
        parent = d.ecGenesisBlock,
        minerRewardL2Address = otherMiner1.elRewardAddress
      )
      d.ecClients.setBlockLogs(ecBlock1.hash, Bridge.ElSentNativeEventTopic, Nil)
      d.ecClients.addKnown(ecBlock1)
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(otherMiner1.account, ecBlock1, d.blockchain.height))

      waitForCS[FollowingChain](d, c) { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock1.hash
      }

      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBadBlock2 $ecBadBlock2Hash")
      d.advanceNewBlocks(otherMiner1.address)

      val ecBadBlock2 = d.createNextEcBlock(
        hash = ecBadBlock2Hash,
        parent = ecBlock1,
        minerRewardL2Address = otherMiner1.elRewardAddress,
        withdrawals = Vector(d.createWithdrawal(0, otherMiner1.elRewardAddress))
      )
      d.ecClients.setBlockLogs(ecBadBlock2.hash, Bridge.ElSentNativeEventTopic, Nil)
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(otherMiner1.account, ecBadBlock2, d.blockchain.height))

      waitForCS[FollowingChain](d, c) { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe ecBadBlock2.hash
      }

      step(s"Start a new epoch of thisMiner ${thisMiner.address} with alternative chain ecBlock2 $ecBlock2Hash")
      d.advanceNewBlocks(thisMiner.address)

      val ecBlock2 = d.createNextEcBlock(
        hash = ecBlock2Hash,
        parent = ecBlock1,
        minerRewardL2Address = thisMiner.elRewardAddress,
        withdrawals = Vector(d.createWithdrawal(0, otherMiner1.elRewardAddress))
      )
      d.ecClients.setBlockLogs(ecBlock2.hash, Bridge.ElSentNativeEventTopic, Nil)
      d.ecClients.willForge(ecBlock2)

      val ecIgnoredBlock3 = d.createNextEcBlock(
        hash = d.createBlockHash("0-1-i"),
        parent = ecBlock2,
        minerRewardL2Address = thisMiner.elRewardAddress
      )
      d.ecClients.setBlockLogs(ecIgnoredBlock3.hash, Bridge.ElSentNativeEventTopic, Nil)
      d.ecClients.willForge(ecIgnoredBlock3)

      waitForCS[Mining](d, c) { s =>
        val ci = s.nodeChainInfo.left.value
        ci.referenceBlock.hash shouldBe ecBlock1.hash
      }

      d.advanceMining()
      d.forgeFromUtxPool()

      waitForCS[Mining](d, c) { s =>
        val ci = s.nodeChainInfo.value
        ci.lastBlock.hash shouldBe ecBlock2.hash
      }

      step(s"Continue an alternative chain by otherMiner2 ${otherMiner2.address} with ecBadBlock3 $ecBadBlock3Hash")
      d.advanceNewBlocks(otherMiner2.address)
      val ecBlock3Epoch = d.blockchain.height
      val ecBlock3 = d.createNextEcBlock(
        hash = ecBadBlock3Hash,
        parent = ecBlock2,
        minerRewardL2Address = otherMiner2.elRewardAddress,
        withdrawals = Vector(d.createWithdrawal(1, otherMiner2.elRewardAddress))
      )
      d.ecClients.setBlockLogs(ecBadBlock3Hash, Bridge.ElSentNativeEventTopic, Nil)
      d.appendMicroBlockAndVerify(chainContract.extendAltChainV3(otherMiner2.account, 1, ecBlock3, ecBlock3Epoch))

      waitForCS[FollowingChain](d, c) { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock3.hash
        s.nextExpectedEcBlock.value shouldBe ecBlock3.hash
      }

      step(s"Continue an alternative chain by thisMiner ${thisMiner.address}")
      d.advanceNewBlocks(thisMiner.address)

      d.advanceWaitRequestedBlock()
      d.advanceWaitRequestedBlock()

      withClue(s"Still wait for ecBlock3 ${ecBlock3.hash}: ") {
        waitForCS[FollowingChain](d, c) { s =>
          s.nodeChainInfo.isMain shouldBe false
          s.nodeChainInfo.lastBlock.hash shouldBe ecBlock3.hash
          s.nextExpectedEcBlock.value shouldBe ecBlock3.hash
        }
      }
    }

    "We haven't mined the alternative chain before and EC-block doesn't come - then we wait for a new alternative chain" in withConsensusClient() {
      (d, c) =>
        val ecBlock1Hash    = d.createBlockHash("0")
        val ecBadBlock2Hash = d.createBlockHash("0-0")
        val ecBlock2Hash    = d.createBlockHash("0-1")
        val ecBlock3Hash    = d.createBlockHash("0-1-1")

        step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBlock1 $ecBlock1Hash")
        d.advanceNewBlocks(otherMiner1.address)
        val ecBlock1 = d.createNextEcBlock(
          hash = ecBlock1Hash,
          parent = d.ecGenesisBlock,
          minerRewardL2Address = otherMiner1.elRewardAddress
        )
        d.ecClients.setBlockLogs(ecBlock1.hash, Bridge.ElSentNativeEventTopic, Nil)
        d.ecClients.addKnown(ecBlock1)
        d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(otherMiner1.account, ecBlock1, d.blockchain.height))

        waitForCS[FollowingChain](d, c) { s =>
          s.nodeChainInfo.isMain shouldBe true
          s.nodeChainInfo.lastBlock.hash shouldBe ecBlock1.hash
        }

        step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBadBlock2 $ecBadBlock2Hash")
        d.advanceNewBlocks(otherMiner1.address)
        val ecBadBlock2Epoch = d.blockchain.height
        val ecBadBlock2 = d.createNextEcBlock(
          hash = ecBadBlock2Hash,
          parent = ecBlock1,
          minerRewardL2Address = otherMiner1.elRewardAddress,
          withdrawals = Vector(d.createWithdrawal(0, otherMiner1.elRewardAddress))
        )
        d.ecClients.setBlockLogs(ecBadBlock2.hash, Bridge.ElSentNativeEventTopic, Nil)
        d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(otherMiner1.account, ecBadBlock2, ecBadBlock2Epoch))

        waitForCS[FollowingChain](d, c) { s =>
          s.nodeChainInfo.isMain shouldBe true
          s.nodeChainInfo.lastBlock.hash shouldBe ecBadBlock2.hash
        }

        step(s"Start a new epoch of otherMiner2 ${otherMiner2.address} with alternative chain ecBlock2 $ecBlock2Hash")
        d.advanceNewBlocks(otherMiner2.address)

        waitForCS[WaitForNewChain](d, c) { s =>
          s.chainSwitchInfo.referenceBlock.hash shouldBe ecBlock1.hash
        }

        val ecBlock2 = d.createNextEcBlock(
          hash = ecBlock2Hash,
          parent = ecBlock1,
          minerRewardL2Address = otherMiner2.elRewardAddress,
          withdrawals = Vector(d.createWithdrawal(0, otherMiner1.elRewardAddress))
        )
        d.ecClients.setBlockLogs(ecBlock2.hash, Bridge.ElSentNativeEventTopic, Nil)
        d.appendMicroBlockAndVerify(chainContract.startAltChainV3(otherMiner2.account, ecBlock2, d.blockchain.height))

        waitForCS[FollowingChain](d, c) { s =>
          s.nodeChainInfo.isMain shouldBe false
          s.nodeChainInfo.lastBlock.hash shouldBe ecBlock2.hash
          s.nextExpectedEcBlock.value shouldBe ecBlock2.hash
        }

        d.receiveNetworkBlock(ecBlock2, otherMiner2.account, d.blockchain.height)
        waitForCS[FollowingChain](d, c) { s =>
          s.nodeChainInfo.isMain shouldBe false
          s.nodeChainInfo.lastBlock.hash shouldBe ecBlock2.hash
          s.nextExpectedEcBlock shouldBe empty
        }

        step(s"Continue an alternative chain by otherMiner2 ${otherMiner2.address} with ecBlock3 $ecBlock3Hash")
        d.advanceNewBlocks(otherMiner2.address)
        val ecBlock3Epoch = d.blockchain.height
        val ecBlock3 = d.createNextEcBlock(
          hash = ecBlock3Hash,
          parent = ecBlock2,
          minerRewardL2Address = otherMiner2.elRewardAddress,
          withdrawals = Vector(d.createWithdrawal(1, otherMiner2.elRewardAddress))
        )
        d.ecClients.setBlockLogs(ecBlock3Hash, Bridge.ElSentNativeEventTopic, Nil)
        d.appendMicroBlockAndVerify(chainContract.extendAltChainV3(otherMiner2.account, 1, ecBlock3, ecBlock3Epoch))

        waitForCS[FollowingChain](d, c) { s =>
          s.nodeChainInfo.isMain shouldBe false
          s.nodeChainInfo.lastBlock.hash shouldBe ecBlock3.hash
          s.nextExpectedEcBlock.value shouldBe ecBlock3.hash
        }

        step(s"Start a new epoch of thisMiner ${thisMiner.address}")
        d.advanceNewBlocks(thisMiner.address)
        waitForCS[WaitForNewChain](d, c) { s =>
          s.chainSwitchInfo.referenceBlock.hash shouldBe ecBlock1.hash
        }
    }
  }
}
