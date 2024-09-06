package units

import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet
import units.ELUpdater.State.ChainStatus.{FollowingChain, Mining, WaitForNewChain}
import units.ELUpdater.WaitRequestedBlockTimeout
import units.client.contract.HasConsensusLayerDappTxHelpers.defaultFees
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
      additionalBalances = List(AddrWithBalance(transferReceiver.toAddress, defaultFees.chainContract.withdrawFee))
    )
    .withEnabledElMining

  "We're on the main chain and" - {
    def test(f: (ExtensionDomain, EcBlock, Int) => Unit): Unit = withExtensionDomain() { d =>
      val ecBlock1 = d.createEcBlockBuilder("0", otherMiner1).buildAndSetLogs()
      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBlock1 ${ecBlock1.hash}")
      d.advanceNewBlocks(otherMiner1.address)
      d.ecClients.addKnown(ecBlock1)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, ecBlock1))
      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock1.hash
      }

      val ecBlock2 = d.createEcBlockBuilder("0-0", otherMiner1, ecBlock1).rewardPrevMiner().buildAndSetLogs()
      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBlock2 ${ecBlock2.hash}")
      d.advanceNewBlocks(otherMiner1.address)
      val ecBlock2Epoch = d.blockchain.height
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, ecBlock2))

      d.waitForCS[FollowingChain](s"Waiting ecBlock2 ${ecBlock2.hash}") { s =>
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock2.hash
        s.nextExpectedEcBlock.value shouldBe ecBlock2.hash
      }

      step(s"Start a new epoch of thisMiner ${thisMiner.address}")
      d.advanceNewBlocks(thisMiner.address)

      f(d, ecBlock2, ecBlock2Epoch)
    }

    "EC-block comes within timeout - then we continue forging" in test { (d, ecBlock2, ecBlock2Epoch) =>
      d.advanceElu(WaitRequestedBlockTimeout - 1.millis)
      d.waitForCS[FollowingChain](s"Still waiting ecBlock2 ${ecBlock2.hash}") { s =>
        s.nextExpectedEcBlock.value shouldBe ecBlock2.hash
      }

      step(s"Receive EC-block ${ecBlock2.hash} from network")
      d.receiveNetworkBlock(ecBlock2, otherMiner1.account, ecBlock2Epoch)
      d.triggerScheduledTasks()

      d.ecClients.willForge(d.createEcBlockBuilder("0-0-0", otherMiner1, ecBlock2).rewardPrevMiner().build())

      d.waitForCS[Mining]("Continue") { s =>
        s.nodeChainInfo.isRight shouldBe true
      }
    }

    "EC-block doesn't come - then we start an alternative chain" in test { (d, _, _) =>
      d.waitForCS[WaitForNewChain](s"Switched to alternative chain") { _ => }
    }
  }

  "We're on the alternative chain and" - {
    "EC-block comes within timeout - then we continue forging" in withExtensionDomain() { d =>
      val ecBlock1 = d.createEcBlockBuilder("0", otherMiner1).buildAndSetLogs()
      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBlock1 ${ecBlock1.hash}")
      d.advanceNewBlocks(otherMiner1.address)
      d.ecClients.addKnown(ecBlock1)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, ecBlock1))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock1.hash
      }

      val ecBadBlock2 = d.createEcBlockBuilder("0-0", otherMiner1, ecBlock1).rewardPrevMiner().buildAndSetLogs()
      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBadBlock2 ${ecBadBlock2.hash}")
      d.advanceNewBlocks(otherMiner1.address)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, ecBadBlock2))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe ecBadBlock2.hash
      }

      val ecBlock2 = d.createEcBlockBuilder("0-1", otherMiner2, ecBlock1).rewardPrevMiner().buildAndSetLogs()
      step(s"Start a new epoch of otherMiner2 ${otherMiner2.address} with alternative chain ecBlock2 ${ecBlock2.hash}")
      d.advanceNewBlocks(otherMiner2.address)

      d.waitForCS[WaitForNewChain]() { s =>
        s.chainSwitchInfo.referenceBlock.hash shouldBe ecBlock1.hash
      }

      d.appendMicroBlockAndVerify(d.chainContract.startAltChain(otherMiner2.account, ecBlock2))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock2.hash
        s.nextExpectedEcBlock.value shouldBe ecBlock2.hash
      }

      d.receiveNetworkBlock(ecBlock2, otherMiner2.account, d.blockchain.height)
      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock2.hash
        s.nextExpectedEcBlock shouldBe empty
      }

      val ecBlock3 = d.createEcBlockBuilder("0-1-1", otherMiner2, parent = ecBlock2).rewardPrevMiner(1).buildAndSetLogs()
      step(s"Continue an alternative chain by otherMiner2 ${otherMiner2.address} with ecBlock3 ${ecBlock3.hash}")
      d.advanceNewBlocks(otherMiner2.address)
      val ecBlock3Epoch = d.blockchain.height
      d.appendMicroBlockAndVerify(d.chainContract.extendAltChain(otherMiner2.account, ecBlock3, chainId = 1))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock3.hash
        s.nextExpectedEcBlock.value shouldBe ecBlock3.hash
      }

      step(s"Start a new epoch of thisMiner ${thisMiner.address}")
      d.advanceNewBlocks(thisMiner.address)
      d.advanceConsensusLayerChanged()
      d.advanceElu(WaitRequestedBlockTimeout - 1.millis)

      d.waitForCS[FollowingChain](s"Waiting ecBlock3 ${ecBlock3.hash}") { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock3.hash
        s.nextExpectedEcBlock.value shouldBe ecBlock3.hash
      }

      step(s"Receive ecBlock3 ${ecBlock3.hash}")
      d.receiveNetworkBlock(ecBlock3, thisMiner.account, ecBlock3Epoch)

      d.ecClients.willForge(d.createEcBlockBuilder("0-1-1-1", thisMiner, ecBlock3).rewardPrevMiner(2).build())
      d.waitForCS[Mining]() { s =>
        s.nodeChainInfo.value.isMain shouldBe false
      }
    }

    "We mined before the alternative chain before and EC-block doesn't come - then we still wait for it" in withExtensionDomain() { d =>
      val ecBlock1 = d.createEcBlockBuilder("0", otherMiner1).buildAndSetLogs()
      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBlock1 ${ecBlock1.hash}")
      d.advanceNewBlocks(otherMiner1.address)
      d.ecClients.addKnown(ecBlock1)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, ecBlock1))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe ecBlock1.hash
      }

      val ecBadBlock2 = d.createEcBlockBuilder("0-0", otherMiner1, ecBlock1).rewardPrevMiner().buildAndSetLogs()
      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBadBlock2 ${ecBadBlock2.hash}")
      d.advanceNewBlocks(otherMiner1.address)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, ecBadBlock2))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe ecBadBlock2.hash
      }

      val ecBlock2 = d.createEcBlockBuilder("0-1", thisMiner, ecBlock1).rewardPrevMiner().buildAndSetLogs()
      step(s"Start a new epoch of thisMiner ${thisMiner.address} with alternative chain ecBlock2 ${ecBlock2.hash}")
      d.advanceNewBlocks(thisMiner.address)
      d.ecClients.willForge(ecBlock2)
      d.ecClients.willForge(d.createEcBlockBuilder("0-1-i", thisMiner, ecBlock2).buildAndSetLogs())

      d.waitForCS[Mining]() { s =>
        val ci = s.nodeChainInfo.left.value
        ci.referenceBlock.hash shouldBe ecBlock1.hash
      }

      d.advanceMining()
      d.forgeFromUtxPool()

      d.waitForCS[Mining]() { s =>
        val ci = s.nodeChainInfo.value
        ci.lastBlock.hash shouldBe ecBlock2.hash
      }

      val ecBadBlock3 = d.createEcBlockBuilder("0-1-1", otherMiner2, ecBlock2).rewardMiner(otherMiner2.elRewardAddress, 1).buildAndSetLogs()
      step(s"Continue an alternative chain by otherMiner2 ${otherMiner2.address} with ecBadBlock3 ${ecBadBlock3.hash}")
      d.advanceNewBlocks(otherMiner2.address)
      d.appendMicroBlockAndVerify(d.chainContract.extendAltChain(otherMiner2.account, ecBadBlock3, chainId = 1))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe ecBadBlock3.hash
        s.nextExpectedEcBlock.value shouldBe ecBadBlock3.hash
      }

      step(s"Continue an alternative chain by thisMiner ${thisMiner.address}")
      d.advanceNewBlocks(thisMiner.address)

      d.advanceWaitRequestedBlock()
      d.advanceWaitRequestedBlock()

      d.waitForCS[FollowingChain](s"Still wait for ecBadBlock3 ${ecBadBlock3.hash}") { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe ecBadBlock3.hash
        s.nextExpectedEcBlock.value shouldBe ecBadBlock3.hash
      }
    }

    "We haven't mined the alternative chain before and EC-block doesn't come - then we wait for a new alternative chain" in
      withExtensionDomain() { d =>
        val ecBlock1 = d.createEcBlockBuilder("0", otherMiner1).buildAndSetLogs()
        step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBlock1 ${ecBlock1.hash}")
        d.advanceNewBlocks(otherMiner1.address)
        d.ecClients.addKnown(ecBlock1)
        d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, ecBlock1))

        d.waitForCS[FollowingChain]() { s =>
          s.nodeChainInfo.isMain shouldBe true
          s.nodeChainInfo.lastBlock.hash shouldBe ecBlock1.hash
        }

        val ecBadBlock2 = d.createEcBlockBuilder("0-0", otherMiner1, ecBlock1).rewardPrevMiner().buildAndSetLogs()
        step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with ecBadBlock2 ${ecBadBlock2.hash}")
        d.advanceNewBlocks(otherMiner1.address)
        d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, ecBadBlock2))

        d.waitForCS[FollowingChain]() { s =>
          s.nodeChainInfo.isMain shouldBe true
          s.nodeChainInfo.lastBlock.hash shouldBe ecBadBlock2.hash
        }

        val ecBlock2 = d.createEcBlockBuilder("0-1", otherMiner2, ecBlock1).rewardPrevMiner().buildAndSetLogs()
        step(s"Start a new epoch of otherMiner2 ${otherMiner2.address} with alternative chain ecBlock2 ${ecBlock2.hash}")
        d.advanceNewBlocks(otherMiner2.address)

        d.waitForCS[WaitForNewChain]() { s =>
          s.chainSwitchInfo.referenceBlock.hash shouldBe ecBlock1.hash
        }

        d.appendMicroBlockAndVerify(d.chainContract.startAltChain(otherMiner2.account, ecBlock2))

        d.waitForCS[FollowingChain]() { s =>
          s.nodeChainInfo.isMain shouldBe false
          s.nodeChainInfo.lastBlock.hash shouldBe ecBlock2.hash
          s.nextExpectedEcBlock.value shouldBe ecBlock2.hash
        }

        d.receiveNetworkBlock(ecBlock2, otherMiner2.account, d.blockchain.height)
        d.waitForCS[FollowingChain]() { s =>
          s.nodeChainInfo.isMain shouldBe false
          s.nodeChainInfo.lastBlock.hash shouldBe ecBlock2.hash
          s.nextExpectedEcBlock shouldBe empty
        }

        val ecBlock3 = d.createEcBlockBuilder("0-1-1", otherMiner2, ecBlock2).rewardPrevMiner(1).buildAndSetLogs()
        step(s"Continue an alternative chain by otherMiner2 ${otherMiner2.address} with ecBlock3 ${ecBlock3.hash}")
        d.advanceNewBlocks(otherMiner2.address)
        d.appendMicroBlockAndVerify(d.chainContract.extendAltChain(otherMiner2.account, ecBlock3, chainId = 1))

        d.waitForCS[FollowingChain]() { s =>
          s.nodeChainInfo.isMain shouldBe false
          s.nodeChainInfo.lastBlock.hash shouldBe ecBlock3.hash
          s.nextExpectedEcBlock.value shouldBe ecBlock3.hash
        }

        step(s"Start a new epoch of thisMiner ${thisMiner.address}")
        d.advanceNewBlocks(thisMiner.address)
        d.waitForCS[WaitForNewChain]() { s =>
          s.chainSwitchInfo.referenceBlock.hash shouldBe ecBlock1.hash
        }
      }
  }
}
