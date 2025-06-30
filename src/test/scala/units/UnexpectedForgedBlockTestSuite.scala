package units

import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.wallet.Wallet
import units.client.engine.model.GetLogsResponseEntry
import units.el.StandardBridge
import units.eth.EthNumber

class UnexpectedForgedBlockTestSuite extends BaseTestSuite {
  private val thisMiner = ElMinerSettings(Wallet.generateNewAccount(super.defaultSettings.walletSeed, 0))

  override protected val defaultSettings: TestSettings = super.defaultSettings
    .copy(initialMiners = List(thisMiner))
    .withEnabledElMining

  // Real case: wrong deployment causes lack of expected events (Asset registration, transfers)
  "Miner stops mining in its epoch, don't broadcast and confirm an unexpected block" in withExtensionDomain() { d =>
    d.advanceNewBlocks(thisMiner)

    val ecBlockLogs = List(
      GetLogsResponseEntry( // Unexpected transfer
        logIndex = EthNumber(0),
        address = StandardBridgeAddress,
        data = "0x00000000000000000000000000000000000000000000000006f05b59d3b20000",
        topics = List(
          StandardBridge.ERC20BridgeFinalized.Topic,
          "0x0000000000000000000000009b8397f1b0fecd3a1a40cdd5e8221fa461898517",
          "0x000000000000000000000000dc1c695f29d77b4ea04281c4833730f5efd0209f",
          "0x000000000000000000000000aaaa00000000000000000000000000000000aaaa"
        ),
        transactionHash = "0x7ee4741de92f8ab1f34a77e9f12bd414426c23ecee9bc01f464c716866cf1690"
      )
    )
    val ecBlock1 = d.createEcBlockBuilder("0", thisMiner).buildAndSetLogs(ecBlockLogs)
    d.ecClients.willForge(ecBlock1)
    d.ecClients.willForge(d.createEcBlockBuilder("0-i", thisMiner, ecBlock1).build())

    d.advanceConsensusLayerChanged()
    d.ecClients.miningAttempts shouldBe 1

    d.advanceMining()                     // Triggers getting a payload and validation
    d.ecClients.miningAttempts shouldBe 2 // Change head and start creating a new payload

    withClue("Block wasn't broadcasted:") {
      d.pollSentNetworkBlock() shouldBe empty
    }

    withClue("Block wasn't confirmed:") {
      d.collectUtx() shouldBe empty
    }

    d.advanceMining()
    withClue("No new mining attempts:") {
      d.ecClients.miningAttempts shouldBe 2
    }
  }
}
