package units

import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet

class MultiplePrivateKeysTestSuite extends BaseIntegrationTestSuite {
  private val miner = ElMinerSettings(Wallet.generateNewAccount(super.defaultSettings.walletSeed, 0))

  private val miner1 = ElMinerSettings(TxHelpers.signer(1))
  private val miner2 = ElMinerSettings(TxHelpers.signer(2))

  val settings: TestSettings = super.defaultSettings
    .copy(initialMiners = List(miner, miner1, miner2))
    .withEnabledElMining
//    .withPrivateKeys(Seq(TxHelpers.signer(1).privateKey, TxHelpers.signer(2).privateKey)) // TODO:

  "Mining starts" in {
    val miningAttempts: Int = 1
    withExtensionDomain(settings) { d =>
      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(miner.address)
      d.advanceConsensusLayerChanged()

      d.ecClients.miningAttempts shouldBe miningAttempts
    }
  }
}
