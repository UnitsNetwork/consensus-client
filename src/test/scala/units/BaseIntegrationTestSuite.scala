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
import org.scalatest.exceptions.TestFailedException
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues}
import units.Bridge.ElSentNativeEvent
import units.ELUpdater.State.{ChainStatus, Working}
import units.ELUpdater.{ClChangedProcessingDelay, MiningRetryInterval, WaitForReferenceConfirmInterval, WaitRequestedBlockTimeout}
import units.client.contract.HasConsensusLayerDappTxHelpers
import units.client.http.model.GetLogsResponseEntry
import units.eth.{EthAddress, Gwei}
import units.test.CustomMatchers
import units.util.HexBytesConverter

import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.Using

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

  def withExtensionDomain[A](settings: TestSettings = defaultSettings)(test: ExtensionDomain => A): A =
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
          elMinerDefaultReward = elMinerDefaultReward
        )

        require(domain.l2Config.chainContractAddress == chainContractAddress, "Check settings")
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

  protected def withConsensusClient[R](settings: TestSettings = defaultSettings)(f: (ExtensionDomain, ConsensusClient) => R): R =
    withExtensionDomain(settings) { d =>
      Using(
        new ConsensusClient(
          d.l2Config,
          d.extensionContext,
          d.ecClients.engineApi,
          d.ecClients.ecApi,
          d.blockObserver,
          d.allChannels,
          d.globalScheduler,
          d.eluScheduler,
          () => {}
        )
      ) { client =>
        d.triggers = d.triggers.appended(client)

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
        f(d, client)
      }((resource: ConsensusClient) => {
        log.trace("Resources cleanup")
        val r = resource.shutdown()
        d.triggerScheduledTasks()
        Await.result(r, 10.seconds)
      }).get
    }

  protected val defaultMaxTimeout =
    List(WaitForReferenceConfirmInterval, ClChangedProcessingDelay, MiningRetryInterval, WaitRequestedBlockTimeout).max + 1.millis
  protected val defaultInterval = ClChangedProcessingDelay

  protected def waitForWorking(
      extensionDomain: ExtensionDomain,
      consensusClient: ConsensusClient,
      title: String = "",
      maxTimeout: FiniteDuration = defaultMaxTimeout,
      interval: FiniteDuration = defaultInterval
  )(f: Working[?] => Unit): Unit = {
    val logPrefix = if (title.isEmpty) "waitForWorking" else s"waitForWorking($title)"
    log.trace(logPrefix)
    val maxAttempts = (maxTimeout / interval).toInt
    @tailrec
    def loop(currAttempt: Int, lastException: Throwable): Unit = {
      currAttempt match {
        case 0                     => // Do nothing
        case 1                     => extensionDomain.triggerScheduledTasks(silent = true)
        case x if x <= maxAttempts => extensionDomain.advanceAll(interval, silent = true)
        case _ =>
          log.warn(s"$logPrefix: $maxAttempts attempts are out")
          throw lastException
      }

      try {
        f(is[Working[?]](consensusClient.elu.state))
        log.trace(s"$logPrefix: successful after ${interval * math.max(0, currAttempt - 1)} (${currAttempt - 1} attempts)")
      } catch {
        case e: Throwable => loop(currAttempt + 1, e)
      }
    }

    loop(0, new TestFailedException(s"$maxAttempts attempts are out", 0))
  }

  protected def waitForCS[CS <: ChainStatus: ClassTag](
      extensionDomain: ExtensionDomain,
      consensusClient: ConsensusClient,
      title: String = ""
  )(f: CS => Unit): Unit = waitForWorking(extensionDomain, consensusClient, title) { s => f(is[CS](s.chainStatus)) }

  protected def mkPayloadId(): String = {
    val bytes = new Array[Byte](8)
    ThreadLocalRandom.current().nextBytes(bytes)
    HexBytesConverter.toHex(bytes)
  }

  protected def step(name: String): Unit = log.info(s"========= $name =========")

  protected def getLogsResponseEntry(event: ElSentNativeEvent): GetLogsResponseEntry =
    GetLogsResponseEntry(elBridgeAddress, Bridge.ElSentNativeEvent.encodeArgs(event), List(Bridge.ElSentNativeEventTopic))
}
