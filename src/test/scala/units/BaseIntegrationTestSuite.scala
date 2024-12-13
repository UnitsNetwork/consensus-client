package units

import com.wavesplatform.database.{RDB, loadActiveLeases}
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.events.BlockchainUpdateTriggers
import com.wavesplatform.state.BlockchainUpdaterImpl
import com.wavesplatform.test.{BaseSuite, NumericExt}
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.utils.ScorexLogging
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues}
import units.client.engine.model.GetLogsResponseEntry
import units.el.Bridge
import units.el.Bridge.ElSentNativeEvent
import units.eth.{EthAddress, Gwei}
import units.test.CustomMatchers
import units.util.HexBytesConverter

import java.util.concurrent.ThreadLocalRandom

trait BaseIntegrationTestSuite
    extends AnyFreeSpec
    with BaseSuite
    with ScorexLogging
    with WithDomain
    with BeforeAndAfterAll
    with EitherValues
    with OptionValues
    with CustomMatchers {
  protected def defaultSettings      = TestSettings.Default
  protected val elMinerDefaultReward = Gwei.ofRawGwei(2_000_000_000L)
  protected val elBridgeAddress      = EthAddress.unsafeFrom("0x0000000000000000000000000000000000006a7e")

  protected def withExtensionDomain[R](settings: TestSettings = defaultSettings)(f: ExtensionDomain => R): R =
    withExtensionDomainUninitialized(settings) { d =>
      log.debug("EL init")
      val txs =
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

      d.appendBlock(txs*)
      d.advanceConsensusLayerChanged()
      f(d)
    }

  private def withExtensionDomainUninitialized[R](settings: TestSettings)(test: ExtensionDomain => R): R =
    withRocksDBWriter(settings.wavesSettings) { blockchain =>
      var d: ExtensionDomain = null
      val bcu = new BlockchainUpdaterImpl(
        blockchain,
        settings.wavesSettings,
        ntpTime,
        BlockchainUpdateTriggers.combined(d.triggers),
        loadActiveLeases(rdb, _, _)
      )

      try {
        d = new ExtensionDomain(
          rdb = new RDB(rdb.db, rdb.txMetaHandle, rdb.txHandle, rdb.txSnapshotHandle, rdb.apiHandle, Seq.empty),
          blockchainUpdater = bcu,
          rocksDBWriter = blockchain,
          settings = settings.wavesSettings,
          elBridgeAddress = elBridgeAddress,
          elMinerDefaultReward = elMinerDefaultReward
        )

        d.wallet.generateNewAccounts(2) // Enough for now

        val balances = List(
          AddrWithBalance(TxHelpers.defaultAddress, 1_000_000.waves),
          AddrWithBalance(d.chainContractAddress, 10.waves)
        ) ++ settings.finalAdditionalBalances

        val genesis = balances.map { case AddrWithBalance(address, amount) =>
          TxHelpers.genesis(address, amount)
        }

        if (genesis.nonEmpty)
          d.appendBlock(
            createGenesisWithStateHash(
              genesis,
              fillStateHash = blockchain.supportsLightNodeBlockFields(),
              Some(settings.wavesSettings.blockchainSettings.genesisSettings.initialBaseTarget)
            )
          )

        test(d)
      } finally {
        Option(d).foreach(_.close())
        bcu.shutdown()
      }
    }

  protected def mkPayloadId(): String = {
    val bytes = new Array[Byte](8)
    ThreadLocalRandom.current().nextBytes(bytes)
    HexBytesConverter.toHex(bytes)
  }

  protected def step(name: String): Unit = log.info(s"========= $name =========")

  protected def getLogsResponseEntry(event: ElSentNativeEvent): GetLogsResponseEntry =
    GetLogsResponseEntry(elBridgeAddress, Bridge.ElSentNativeEvent.encodeArgs(event), List(Bridge.ElSentNativeEventTopic), "")
}
