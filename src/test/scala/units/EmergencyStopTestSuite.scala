package units

import com.wavesplatform.wallet.Wallet
import units.ELUpdater.State.ChainStatus.Mining

class EmergencyStopTestSuite extends BaseTestSuite {
  private val miner = ElMinerSettings(Wallet.generateNewAccount(super.defaultSettings.walletSeed, 0))

  override protected val defaultSettings: TestSettings = super.defaultSettings
    .copy(initialMiners = List(miner))
    .withEnabledElMining

  "Emergency stop test" in withExtensionDomain() { d =>
    step("Start mining")
    d.appendBlock()
    d.ecClients.willForge(d.createEcBlockBuilder("0", miner).build())
    d.advanceConsensusLayerChanged()
    d.ecClients.miningAttempts shouldBe 1

    step("Emergency stop")
    d.appendMicroBlock(d.ChainContract.stop())
    d.ecClients.willForge(d.createEcBlockBuilder("0-0", miner).build())
    d.advanceConsensusLayerChanged()
    d.ecClients.miningAttempts shouldBe 1 // Not changed

    step("Continue")
    // We can't start forging from Starting state...
    d.appendBlock(d.ChainContract.continue())
    d.advanceConsensusLayerChanged()

    // ...Mining will be possible on next epoch
    d.appendBlock()
    d.waitForCS[Mining]("Continue") { s =>
      s.nodeChainInfo.isRight shouldBe true
    }
  }
}
