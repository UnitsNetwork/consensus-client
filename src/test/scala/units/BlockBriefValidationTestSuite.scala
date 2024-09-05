package units

import com.wavesplatform.transaction.TxHelpers
import units.ELUpdater.State.Working
import units.eth.EthAddress

class BlockBriefValidationTestSuite extends BaseIntegrationTestSuite {
  private val miner = ElMinerSettings(TxHelpers.signer(1))

  override protected val defaultSettings: TestSettings = TestSettings.Default.copy(
    initialMiners = List(miner)
  )

  "Brief validation of EC Block incoming from network" - {
    "accepts if it is valid" in test { d =>
      val ecBlock = d.createEcBlockBuilder("0", miner).build()

      step(s"Receive ecBlock ${ecBlock.hash} from a peer")
      d.receiveNetworkBlock(ecBlock, miner.account)
      withClue("Brief EL block validation:") {
        d.triggerScheduledTasks()
        d.pollSentNetworkBlock() match {
          case Some(sent) => sent.hash shouldBe ecBlock.hash
          case None       => fail(s"${ecBlock.hash} should not be ignored")
        }
      }
    }

    "otherwise ignoring" in test { d =>
      val ecBlock = d.createEcBlockBuilder("0", minerRewardL2Address = EthAddress.empty, parent = d.ecGenesisBlock).build()

      step(s"Receive ecBlock ${ecBlock.hash} from a peer")
      d.receiveNetworkBlock(ecBlock, miner.account)
      withClue("Brief EL block validation:") {
        d.triggerScheduledTasks()
        if (d.pollSentNetworkBlock().nonEmpty) fail(s"${ecBlock.hash} should be ignored, because it is invalid by brief validation rules")
      }
    }
  }

  private def test(f: ExtensionDomain => Unit): Unit = withExtensionDomain() { d =>
    step("Start a new epoch")
    d.advanceNewBlocks(miner.address)
    d.advanceConsensusLayerChanged()

    is[Working[?]](d.consensusClient.elu.state)

    f(d)
  }
}
