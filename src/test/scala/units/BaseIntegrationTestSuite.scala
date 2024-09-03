package units

import com.wavesplatform.account.KeyPair
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
import units.Bridge.ElSentNativeEvent
import units.client.contract.HasConsensusLayerDappTxHelpers
import units.client.http.model.GetLogsResponseEntry
import units.eth.{EthAddress, Gwei}
import units.test.CustomMatchers
import units.util.HexBytesConverter

import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom

trait BaseIntegrationTestSuite
    extends AnyFreeSpec
    with BaseSuite
    with ScorexLogging
    with WithDomain
    with HasConsensusLayerDappTxHelpers
    with BeforeAndAfterAll
    with EitherValues
    with OptionValues
    with CustomMatchers {
  protected def defaultSettings      = TestSettings.Default
  protected val elMinerDefaultReward = Gwei.ofRawGwei(2_000_000_000L)
  protected val elBridgeAddress      = EthAddress.unsafeFrom("0x189643C45cC2782DFd42185d0cD86B71943D6315")

  override val stakingContractAccount: KeyPair = KeyPair("staking-contract".getBytes(StandardCharsets.UTF_8))
  override val chainContractAccount: KeyPair   = KeyPair("chain-contract".getBytes(StandardCharsets.UTF_8))

  protected def withExtensionDomain[R](settings: TestSettings = defaultSettings)(f: ExtensionDomain => R): R =
    withExtensionDomainUninitialized(settings) { d =>
      log.debug("EL init")
      val txs =
        List(
          chainContract.setScript(),
          chainContract.setup(d.ecGenesisBlock, elMinerDefaultReward.amount.longValue(), elBridgeAddress)
        ) ++
          settings.initialMiners
            .flatMap { x =>
              List(
                stakingContract.stakingBalance(x.address, 0, x.stakingBalance, 1, x.stakingBalance),
                chainContract.join(x.account, x.elRewardAddress)
              )
            }

      d.appendBlock(txs*)
      d.advanceConsensusLayerChanged()
      f(d)
    }

  private def withExtensionDomainUninitialized[R](settings: TestSettings = defaultSettings)(test: ExtensionDomain => R): R =
    withRocksDBWriter(settings.wavesSettings) { blockchain =>
      var domain: ExtensionDomain = null
      val bcu = new BlockchainUpdaterImpl(
        blockchain,
        settings.wavesSettings,
        ntpTime,
        BlockchainUpdateTriggers.combined(domain.triggers),
        loadActiveLeases(rdb, _, _)
      )

      try {
        domain = new ExtensionDomain(
          rdb = new RDB(rdb.db, rdb.txMetaHandle, rdb.txHandle, rdb.txSnapshotHandle, rdb.apiHandle, Seq.empty),
          blockchainUpdater = bcu,
          rocksDBWriter = blockchain,
          settings = settings.wavesSettings,
          elMinerDefaultReward = elMinerDefaultReward,
          chainContractAccount = chainContractAccount,
          stakingContractAccount = stakingContractAccount
        )

        domain.wallet.generateNewAccounts(2) // Enough for now

        val balances = List(
          AddrWithBalance(TxHelpers.defaultAddress, 1_000_000.waves),
          AddrWithBalance(stakingContractAddress, 10.waves),
          AddrWithBalance(chainContractAddress, 10.waves)
        ) ++ settings.finalAdditionalBalances

        val genesis = balances.map { case AddrWithBalance(address, amount) =>
          TxHelpers.genesis(address, amount)
        }

        if (genesis.nonEmpty)
          domain.appendBlock(
            createGenesisWithStateHash(
              genesis,
              fillStateHash = blockchain.supportsLightNodeBlockFields(),
              Some(settings.wavesSettings.blockchainSettings.genesisSettings.initialBaseTarget)
            )
          )

        test(domain)
      } finally {
        Option(domain).foreach(_.close())
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
    GetLogsResponseEntry(elBridgeAddress, Bridge.ElSentNativeEvent.encodeArgs(event), List(Bridge.ElSentNativeEventTopic))
}
