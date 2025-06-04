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
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues, TryValues}
import org.web3j.abi.TypeEncoder
import org.web3j.abi.datatypes.generated.Int64
import units.client.engine.model.GetLogsResponseEntry
import units.el.NativeBridge.ElSentNativeEvent
import units.el.{NativeBridge, StandardBridge}
import units.eth.EthNumber
import units.test.CustomMatchers
import units.util.HexBytesConverter

import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom

trait BaseTestSuite
    extends AnyFreeSpec
    with BaseSuite
    with ScorexLogging
    with WithDomain
    with TestDefaults
    with BeforeAndAfterAll
    with EitherValues
    with OptionValues
    with TryValues
    with CustomMatchers {
  protected val chainRegistryAccount: KeyPair = KeyPair("chain-registry".getBytes(StandardCharsets.UTF_8))

  protected def defaultSettings = TestSettings().withChainRegistry(chainRegistryAccount.toAddress)

  protected def withExtensionDomain[R](settings: TestSettings = defaultSettings)(f: ExtensionDomain => R): R =
    withExtensionDomainUninitialized(settings) { d =>
      log.debug("EL init")
      val txs = List(
        d.ChainRegistry.approve(),
        d.ChainContract.setScript(),
        d.ChainContract.setup(
          d.ecGenesisBlock,
          ElMinerDefaultReward.amount.longValue(),
          defaultSettings.daoRewardAccount.map(_.toAddress),
          defaultSettings.daoRewardAmount
        ),
        if (settings.registerWwavesToken)
          d.ChainContract.enableTokenTransfersWithWaves(
            StandardBridgeAddress,
            WWavesAddress,
            activationEpoch = settings.enableTokenTransfersEpoch
          )
        else
          d.ChainContract.enableTokenTransfers(
            StandardBridgeAddress,
            activationEpoch = settings.enableTokenTransfersEpoch
          )
      ) ++ settings.initialMiners.map { x => d.ChainContract.join(x.account, x.elRewardAddress) }

      d.appendBlock(txs*)
      d.advanceConsensusLayerChanged()
      f(d)
    }

  protected def withExtensionDomainUninitialized[R](settings: TestSettings = defaultSettings)(test: ExtensionDomain => R): R =
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
          chainRegistryAccount = chainRegistryAccount,
          nativeBridgeAddress = NativeBridgeAddress,
          standardBridgeAddress = StandardBridgeAddress,
          elMinerDefaultReward = ElMinerDefaultReward
        )

        d.wallet.generateNewAccounts(2) // Enough for now

        val balances = List(
          AddrWithBalance(TxHelpers.defaultAddress, 1_000_000.waves),
          AddrWithBalance(d.chainRegistryAddress, 10.waves),
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
    GetLogsResponseEntry(
      EthNumber(0),
      NativeBridgeAddress,
      NativeBridge.ElSentNativeEvent.encodeArgs(event),
      List(NativeBridge.ElSentNativeEventTopic),
      ""
    )

  protected def getLogsResponseEntry(event: StandardBridge.ERC20BridgeFinalized): GetLogsResponseEntry =
    GetLogsResponseEntry(
      EthNumber(0),
      StandardBridgeAddress,
      TypeEncoder.encode(new Int64(event.amount.raw)),
      List(
        StandardBridge.ERC20BridgeFinalized.Topic,
        event.localToken.hex,
        event.from.hex,
        event.elTo.hex
      ),
      ""
    )
}
