package units

import com.wavesplatform.extensions.ExtensionDomain
import units.ELUpdater.State.Working
import com.wavesplatform.wallet.Wallet

class BlockBriefValidationTestSuite extends BaseIntegrationTestSuite {
  private val miner = ElMinerSettings(Wallet.generateNewAccount(TestSettings.Default.walletSeed, 0))

  override protected val defaultSettings: TestSettings = TestSettings.Default.copy(
    initialMiners = List(miner)
  )

  "Brief validation of EC Block incoming from network" - {
    "accepts if it is valid" in test { d =>
      val ecBlock = d.createNextEcBlock(
        hash = d.createBlockHash("0"),
        parent = d.ecGenesisBlock,
        minerRewardL2Address = miner.elRewardAddress
      )

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
      val ecBlock = d.createNextEcBlock(
        hash = d.createBlockHash("0"),
        parent = d.ecGenesisBlock
      )

      step(s"Receive ecBlock ${ecBlock.hash} from a peer")
      d.receiveNetworkBlock(ecBlock, miner.account)
      withClue("Brief EL block validation:") {
        d.triggerScheduledTasks()
        if (d.pollSentNetworkBlock().nonEmpty) fail(s"${ecBlock.hash} should be ignored, because it is invalid by brief validation rules")
      }
    }
  }

  private def test(f: ExtensionDomain => Unit): Unit = withConsensusClient() { (d, c) =>
    step("Start a new epoch")
    d.advanceNewBlocks(miner.address)
    d.advanceElu()

    is[Working[?]](c.elu.state)

    f(d)
  }
}
