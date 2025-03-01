package units

import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet

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
      d.advanceConsensusLayerChanged()

      d.ecClients.miningAttempts shouldBe 1
    }
  }

  "Wallet is used when private keys list is not provided" in {
    val settings = defaultSettings

    withExtensionDomain(settings) { d =>
      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(minerFromWallet.address)
      d.advanceConsensusLayerChanged()

      d.ecClients.miningAttempts shouldBe 1
    }
  }

  "Mining starts when 1 private key is provided instead of a wallet" in {
    val settings = defaultSettings.withPrivateKeys(Seq(miner1.account.privateKey))

    withExtensionDomain(settings) { d =>
      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(miner1.address)
      d.advanceConsensusLayerChanged()

      d.ecClients.miningAttempts shouldBe 1
    }
  }

  "Mining starts when 2 private keys are provided instead of a wallet" in {
    val settings = defaultSettings.withPrivateKeys(Seq(miner1.account.privateKey, miner2.account.privateKey))

    withExtensionDomain(settings) { d =>
      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(miner1.address)
      d.advanceConsensusLayerChanged()

      d.advanceNewBlocks(miner2.address)
      d.advanceConsensusLayerChanged()

      d.ecClients.miningAttempts shouldBe 2
    }
  }
}
