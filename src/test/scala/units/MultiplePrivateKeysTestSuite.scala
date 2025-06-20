package units

import com.wavesplatform.mining.MultiDimensionalMiningConstraint
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet
import units.ELUpdater.State.ChainStatus.Mining

class MultiplePrivateKeysTestSuite extends BaseTestSuite {
  private val minerFromWallet = ElMinerSettings(Wallet.generateNewAccount(super.defaultSettings.walletSeed, 0))

  private val miner1 = ElMinerSettings(TxHelpers.signer(1))
  private val miner2 = ElMinerSettings(TxHelpers.signer(2))

  override protected val defaultSettings: TestSettings = super.defaultSettings
    // Note: For this test, the chain contract knows all the miners
    .copy(initialMiners = List(minerFromWallet, miner1, miner2))
    .withEnabledElMining

  "Wallet is not used when private keys are provided" in {
    val settings = defaultSettings.withPrivateKeys(Seq(miner1.account.privateKey, miner2.account.privateKey))
    withExtensionDomain(settings) { d =>
      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(minerFromWallet.address)
      d.advanceConsensusLayerChanged()

      d.ecClients.miningAttempts shouldBe 0
    }
  }

  "Wallet is used when private keys list is empty" in {
    val settings = defaultSettings.withPrivateKeys(Seq.empty)

    withExtensionDomain(settings) { d =>
      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(minerFromWallet.address)

      d.ecClients.willForge(d.createEcBlockBuilder("0", minerFromWallet).build())
      d.advanceConsensusLayerChanged()

      d.ecClients.miningAttempts shouldBe 1
    }
  }

  "Wallet is used when private keys list is not provided" in {
    val settings = defaultSettings

    withExtensionDomain(settings) { d =>
      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(minerFromWallet.address)

      d.ecClients.willForge(d.createEcBlockBuilder("0", minerFromWallet).build())
      d.advanceConsensusLayerChanged()

      d.ecClients.miningAttempts shouldBe 1
    }
  }

  "Mining starts when 1 private key is provided instead of a wallet" in {
    val settings = defaultSettings.withPrivateKeys(Seq(miner1.account.privateKey))

    withExtensionDomain(settings) { d =>
      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(miner1.address)

      d.ecClients.willForge(d.createEcBlockBuilder("0", miner1).build())
      d.advanceConsensusLayerChanged()

      d.ecClients.miningAttempts shouldBe 1
    }
  }

  "Mining starts when 2 private keys are provided instead of a wallet" in {
    val settings = defaultSettings.withPrivateKeys(Seq(miner1.account.privateKey, miner2.account.privateKey))

    withExtensionDomain(settings) { d =>
      step("Start epoch of miner1")
      d.advanceNewBlocks(miner1.address)
      d.ecClients.willForge(d.createEcBlockBuilder("0", miner1).build())
      d.advanceConsensusLayerChanged()

      step("Start epoch of miner2")
      d.advanceNewBlocks(miner2.address)
      d.ecClients.willForge(d.createEcBlockBuilder("0", miner2).build())
      d.advanceConsensusLayerChanged()

      d.ecClients.miningAttempts shouldBe 2
    }
  }
}
