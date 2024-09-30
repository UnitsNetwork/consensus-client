package units

import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet
import units.ELUpdater.State.ChainStatus.{FollowingChain, Mining, WaitForNewChain}
import units.ELUpdater.WaitRequestedPayloadTimeout
import units.client.contract.HasConsensusLayerDappTxHelpers.defaultFees
import units.client.engine.model.ExecutionPayload

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
    def test(f: (ExtensionDomain, ExecutionPayload, Int) => Unit): Unit = withExtensionDomain() { d =>
      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with payload1")
      d.advanceNewBlocks(otherMiner1.address)
      val payload1 = d.createPayloadBuilder("0", otherMiner1).buildAndSetLogs()
      d.ecClients.addKnown(payload1)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, payload1))
      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.lastBlock.hash shouldBe payload1.hash
      }

      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with payload2")
      d.advanceNewBlocks(otherMiner1.address)
      val payload2    = d.createPayloadBuilder("0-0", otherMiner1, payload1).rewardPrevMiner().buildAndSetLogs()
      val block2Epoch = d.blockchain.height
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, payload2))

      d.waitForCS[FollowingChain](s"Waiting payload2 ${payload2.hash}") { s =>
        s.nodeChainInfo.lastBlock.hash shouldBe payload2.hash
        s.nextExpectedBlock.map(_.hash).value shouldBe payload2.hash
      }

      step(s"Start a new epoch of thisMiner ${thisMiner.address}")
      d.advanceNewBlocks(thisMiner.address)

      f(d, payload2, block2Epoch)
    }

    "Block payload comes within timeout - then we continue forging" in test { (d, payload2, block2Epoch) =>
      d.advanceElu(WaitRequestedPayloadTimeout - 1.millis)
      d.waitForCS[FollowingChain](s"Still waiting payload2 ${payload2.hash}") { s =>
        s.nextExpectedBlock.map(_.hash).value shouldBe payload2.hash
      }

      step(s"Receive block2 ${payload2.hash} payload2")
      d.receivePayload(payload2, otherMiner1.account, block2Epoch)
      d.triggerScheduledTasks()

      d.ecClients.willForge(d.createPayloadBuilder("0-0-0", otherMiner1, payload2).rewardPrevMiner().build())

      d.waitForCS[Mining]("Continue") { s =>
        s.nodeChainInfo.isRight shouldBe true
      }
    }

    "Block payload doesn't come - then we start an alternative chain" in test { (d, _, _) =>
      d.waitForCS[WaitForNewChain](s"Switched to alternative chain") { _ => }
    }
  }

  "We're on the alternative chain and" - {
    "Block payload comes within timeout - then we continue forging" in withExtensionDomain() { d =>
      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with payload1")
      d.advanceNewBlocks(otherMiner1.address)
      val payload1 = d.createPayloadBuilder("0", otherMiner1).buildAndSetLogs()
      d.ecClients.addKnown(payload1)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, payload1))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe payload1.hash
      }

      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with badPayload2")
      d.advanceNewBlocks(otherMiner1.address)
      val badPayload2 = d.createPayloadBuilder("0-0", otherMiner1, payload1).rewardPrevMiner().buildAndSetLogs()
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, badPayload2))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe badPayload2.hash
      }

      step(s"Start a new epoch of otherMiner2 ${otherMiner2.address} with alternative chain payload2")
      d.advanceNewBlocks(otherMiner2.address)
      val payload2 = d.createPayloadBuilder("0-1", otherMiner2, payload1).rewardPrevMiner().buildAndSetLogs()

      d.waitForCS[WaitForNewChain]() { s =>
        s.chainSwitchInfo.referenceBlock.hash shouldBe payload1.hash
      }

      d.appendMicroBlockAndVerify(d.chainContract.startAltChain(otherMiner2.account, payload2))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe payload2.hash
        s.nextExpectedBlock.map(_.hash).value shouldBe payload2.hash
      }

      d.receivePayload(payload2, otherMiner2.account, d.blockchain.height)
      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe payload2.hash
        s.nextExpectedBlock shouldBe empty
      }

      step(s"Continue an alternative chain by otherMiner2 ${otherMiner2.address} with payload3")
      d.advanceNewBlocks(otherMiner2.address)
      val payload3    = d.createPayloadBuilder("0-1-1", otherMiner2, parentPayload = payload2).rewardPrevMiner(1).buildAndSetLogs()
      val block3Epoch = d.blockchain.height
      d.appendMicroBlockAndVerify(d.chainContract.extendAltChain(otherMiner2.account, payload3, chainId = 1))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe payload3.hash
        s.nextExpectedBlock.map(_.hash).value shouldBe payload3.hash
      }

      step(s"Start a new epoch of thisMiner ${thisMiner.address}")
      d.advanceNewBlocks(thisMiner.address)
      d.advanceConsensusLayerChanged()
      d.advanceElu(WaitRequestedPayloadTimeout - 1.millis)

      d.waitForCS[FollowingChain](s"Waiting payload3 ${payload3.hash}") { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe payload3.hash
        s.nextExpectedBlock.map(_.hash).value shouldBe payload3.hash
      }

      step(s"Receive block3 ${payload3.hash} payload3")
      d.receivePayload(payload3, thisMiner.account, block3Epoch)

      d.ecClients.willForge(d.createPayloadBuilder("0-1-1-1", thisMiner, payload3).rewardPrevMiner(2).build())
      d.waitForCS[Mining]() { s =>
        s.nodeChainInfo.value.isMain shouldBe false
      }
    }

    "We mined before the alternative chain before and block payload doesn't come - then we still wait for it" in withExtensionDomain() { d =>
      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with payload1")
      d.advanceNewBlocks(otherMiner1.address)
      val payload1 = d.createPayloadBuilder("0", otherMiner1).buildAndSetLogs()
      d.ecClients.addKnown(payload1)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, payload1))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe payload1.hash
      }

      step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with badPayload2")
      d.advanceNewBlocks(otherMiner1.address)
      val badPayload2 = d.createPayloadBuilder("0-0", otherMiner1, payload1).rewardPrevMiner().buildAndSetLogs()
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, badPayload2))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe true
        s.nodeChainInfo.lastBlock.hash shouldBe badPayload2.hash
      }

      step(s"Start a new epoch of thisMiner ${thisMiner.address} with alternative chain payload2")
      d.advanceNewBlocks(thisMiner.address)
      val payload2 = d.createPayloadBuilder("0-1", thisMiner, payload1).rewardPrevMiner().buildAndSetLogs()
      d.ecClients.willForge(payload2)
      d.ecClients.willForge(d.createPayloadBuilder("0-1-i", thisMiner, payload2).buildAndSetLogs())

      d.waitForCS[Mining]() { s =>
        val ci = s.nodeChainInfo.left.value
        ci.referenceBlock.hash shouldBe payload1.hash
      }

      d.advanceMining()
      d.forgeFromUtxPool()

      d.waitForCS[Mining]() { s =>
        val ci = s.nodeChainInfo.value
        ci.lastBlock.hash shouldBe payload2.hash
      }

      step(s"Continue an alternative chain by otherMiner2 ${otherMiner2.address} with badPayload3")
      d.advanceNewBlocks(otherMiner2.address)
      val badPayload3 = d.createPayloadBuilder("0-1-1", otherMiner2, payload2).rewardMiner(otherMiner2.elRewardAddress, 1).buildAndSetLogs()
      d.appendMicroBlockAndVerify(d.chainContract.extendAltChain(otherMiner2.account, badPayload3, chainId = 1))

      d.waitForCS[FollowingChain]() { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe badPayload3.hash
        s.nextExpectedBlock.map(_.hash).value shouldBe badPayload3.hash
      }

      step(s"Continue an alternative chain by thisMiner ${thisMiner.address}")
      d.advanceNewBlocks(thisMiner.address)

      d.advanceWaitRequestedBlockPayload()
      d.advanceWaitRequestedBlockPayload()

      d.waitForCS[FollowingChain](s"Still wait for badPayload3 ${badPayload3.hash}") { s =>
        s.nodeChainInfo.isMain shouldBe false
        s.nodeChainInfo.lastBlock.hash shouldBe badPayload3.hash
        s.nextExpectedBlock.map(_.hash).value shouldBe badPayload3.hash
      }
    }

    "We haven't mined the alternative chain before and block payload doesn't come - then we wait for a new alternative chain" in
      withExtensionDomain() { d =>
        step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with payload1")
        d.advanceNewBlocks(otherMiner1.address)
        val payload1 = d.createPayloadBuilder("0", otherMiner1).buildAndSetLogs()
        d.ecClients.addKnown(payload1)
        d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, payload1))

        d.waitForCS[FollowingChain]() { s =>
          s.nodeChainInfo.isMain shouldBe true
          s.nodeChainInfo.lastBlock.hash shouldBe payload1.hash
        }

        step(s"Start a new epoch of otherMiner1 ${otherMiner1.address} with badPayload2")
        d.advanceNewBlocks(otherMiner1.address)
        val badPayload2 = d.createPayloadBuilder("0-0", otherMiner1, payload1).rewardPrevMiner().buildAndSetLogs()
        d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(otherMiner1.account, badPayload2))

        d.waitForCS[FollowingChain]() { s =>
          s.nodeChainInfo.isMain shouldBe true
          s.nodeChainInfo.lastBlock.hash shouldBe badPayload2.hash
        }

        step(s"Start a new epoch of otherMiner2 ${otherMiner2.address} with alternative chain payload2")
        d.advanceNewBlocks(otherMiner2.address)
        val payload2 = d.createPayloadBuilder("0-1", otherMiner2, payload1).rewardPrevMiner().buildAndSetLogs()

        d.waitForCS[WaitForNewChain]() { s =>
          s.chainSwitchInfo.referenceBlock.hash shouldBe payload1.hash
        }

        d.appendMicroBlockAndVerify(d.chainContract.startAltChain(otherMiner2.account, payload2))

        d.waitForCS[FollowingChain]() { s =>
          s.nodeChainInfo.isMain shouldBe false
          s.nodeChainInfo.lastBlock.hash shouldBe payload2.hash
          s.nextExpectedBlock.map(_.hash).value shouldBe payload2.hash
        }

        d.receivePayload(payload2, otherMiner2.account, d.blockchain.height)
        d.waitForCS[FollowingChain]() { s =>
          s.nodeChainInfo.isMain shouldBe false
          s.nodeChainInfo.lastBlock.hash shouldBe payload2.hash
          s.nextExpectedBlock shouldBe empty
        }

        step(s"Continue an alternative chain by otherMiner2 ${otherMiner2.address} with payload3")
        d.advanceNewBlocks(otherMiner2.address)
        val payload3 = d.createPayloadBuilder("0-1-1", otherMiner2, payload2).rewardPrevMiner(1).buildAndSetLogs()
        d.appendMicroBlockAndVerify(d.chainContract.extendAltChain(otherMiner2.account, payload3, chainId = 1))

        d.waitForCS[FollowingChain]() { s =>
          s.nodeChainInfo.isMain shouldBe false
          s.nodeChainInfo.lastBlock.hash shouldBe payload3.hash
          s.nextExpectedBlock.map(_.hash).value shouldBe payload3.hash
        }

        step(s"Start a new epoch of thisMiner ${thisMiner.address}")
        d.advanceNewBlocks(thisMiner.address)
        d.waitForCS[WaitForNewChain]() { s =>
          s.chainSwitchInfo.referenceBlock.hash shouldBe payload1.hash
        }
      }
  }
}
