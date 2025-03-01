package units

import com.wavesplatform.state.StringDataEntry
import com.wavesplatform.transaction.{DataTransaction, TxHelpers}
import com.wavesplatform.wallet.Wallet

class RegistryTestSuite extends BaseTestSuite {
  private val miner                  = ElMinerSettings(Wallet.generateNewAccount(super.defaultSettings.walletSeed, 0))
  private val irrelevantChainAddress = TxHelpers.signer(10).toAddress

  override protected val defaultSettings: TestSettings = super.defaultSettings
    .copy(initialMiners = List(miner))
    .withEnabledElMining

  "Mining doesn't start when chains registry" - {
    def noMiningTest(registryTxn: ExtensionDomain => List[DataTransaction]): Unit = run(0)(registryTxn(_))

    "doesn't have a key" in noMiningTest(_ => Nil)

    "has only an irrelevant chain" in noMiningTest { d =>
      d.ChainRegistry.approve(irrelevantChainAddress) :: Nil
    }

    "has an invalid key" in noMiningTest { d =>
      val wrongTypeData = StringDataEntry(ELUpdater.registryKey(d.chainContractAddress), "true")
      d.ChainRegistry.setStatus(wrongTypeData) :: Nil
    }

    "has a rejected chain" in noMiningTest(_.ChainRegistry.reject() :: Nil)
  }

  "Mining starts when chain registry has an approved chain" in run(1) { d =>
    List(
      d.ChainRegistry.approve(irrelevantChainAddress),
      d.ChainRegistry.approve()
    )
  }

  private def run(miningAttempts: Int)(registryTxn: ExtensionDomain => List[DataTransaction]): Unit = {
    val settings = defaultSettings
    withExtensionDomainUninitialized(settings) { d =>
      log.debug("EL init")
      val txs = {
        registryTxn(d) ++
          List(
            d.ChainContract.setScript(),
            d.ChainContract.setup(
              d.ecGenesisBlock,
              elMinerDefaultReward.amount.longValue(),
              defaultSettings.daoRewardAccount.map(_.toAddress),
              defaultSettings.daoRewardAmount
            )
          ) ++
          settings.initialMiners.map { x => d.ChainContract.join(x.account, x.elRewardAddress) }
      }

      d.appendBlock(txs*)
      d.advanceConsensusLayerChanged()

      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(miner.address)
      d.advanceConsensusLayerChanged()

      d.ecClients.miningAttempts shouldBe miningAttempts
    }
  }
}
