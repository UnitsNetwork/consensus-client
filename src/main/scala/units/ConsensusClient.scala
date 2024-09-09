package units

import com.wavesplatform.block.{Block, MicroBlock}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.events.BlockchainUpdateTriggers
import com.wavesplatform.extensions.{Extension, Context as ExtensionContext}
import com.wavesplatform.network.{PeerDatabaseImpl, PeerInfo}
import com.wavesplatform.state.{Blockchain, StateSnapshot}
import com.wavesplatform.utils.{LoggerFacade, Schedulers}
import io.netty.channel.Channel
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.execution.schedulers.SchedulerService
import monix.execution.{CancelableFuture, Scheduler}
import net.ceedubs.ficus.Ficus.*
import org.slf4j.LoggerFactory
import sttp.client3.HttpClientSyncBackend
import units.client.engine.{EngineApiClient, HttpEngineApiClient}
import units.client.http.{EcApiClient, HttpEcApiClient}
import units.client.{JwtAuthenticationBackend, LoggingBackend}
import units.network.*

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future
import scala.io.Source

class ConsensusClient(
    config: ClientConfig,
    context: ExtensionContext,
    engineApiClient: EngineApiClient,
    httpApiClient: EcApiClient,
    blockObserver: BlocksObserver,
    allChannels: DefaultChannelGroup,
    globalScheduler: Scheduler,
    eluScheduler: Scheduler,
    ownedResources: AutoCloseable
) extends Extension
    with BlockchainUpdateTriggers {

  def this(context: ExtensionContext, deps: ConsensusClientDependencies) =
    this(
      deps.config,
      context,
      deps.engineApiClient,
      deps.httpApiClient,
      deps.blockObserver,
      deps.allChannels,
      deps.globalScheduler,
      deps.eluScheduler,
      deps
    )

  def this(context: ExtensionContext) = this(context, new ConsensusClientDependencies(context))

  private[units] val elu =
    new ELUpdater(
      httpApiClient,
      engineApiClient,
      context.blockchain,
      context.utx,
      allChannels,
      config,
      context.time,
      context.wallet,
      blockObserver.loadBlock,
      context.broadcastTransaction,
      eluScheduler,
      globalScheduler
    )

  private val blocksStreamCancelable: CancelableFuture[Unit] =
    blockObserver.getBlockStream.foreach { case (ch, block) => elu.executionBlockReceived(block, ch) }(globalScheduler)

  override def start(): Unit = {}

  def shutdown(): Future[Unit] = Future {
    blocksStreamCancelable.cancel()
    elu.close()
    ownedResources.close()
  }(globalScheduler)

  override def onProcessBlock(
      block: Block,
      snapshot: StateSnapshot,
      reward: Option[Long],
      hitSource: ByteStr,
      blockchainBeforeWithReward: Blockchain
  ): Unit = elu.consensusLayerChanged()

  override def onProcessMicroBlock(
      microBlock: MicroBlock,
      snapshot: StateSnapshot,
      blockchainBeforeWithReward: Blockchain,
      totalBlockId: ByteStr,
      totalTransactionsRoot: ByteStr
  ): Unit = elu.consensusLayerChanged()

  override def onRollback(blockchainBefore: Blockchain, toBlockId: ByteStr, toHeight: Int): Unit = {}

  override def onMicroBlockRollback(blockchainBefore: Blockchain, toBlockId: ByteStr): Unit = {}
}

// A helper to create ConsensusClient due to Scala secondary constructors limitations
class ConsensusClientDependencies(context: ExtensionContext) extends AutoCloseable {
  protected lazy val log: LoggerFacade = LoggerFacade(LoggerFactory.getLogger(classOf[ConsensusClient]))

  val config: ClientConfig = context.settings.config.as[ClientConfig]("waves.l2")

  private val blockObserverScheduler = Schedulers.singleThread("block-observer-l2", reporter = { e => log.warn("Error in BlockObserver", e) })
  val globalScheduler: Scheduler     = monix.execution.Scheduler.global
  val eluScheduler: SchedulerService = Scheduler.singleThread("el-updater", reporter = { e => log.warn("Exception in ELUpdater", e) })

  private val httpClientBackend = new LoggingBackend(HttpClientSyncBackend())
  private val maybeAuthenticatedBackend = config.jwtSecretFile match {
    case Some(secretFile) =>
      val src = Source.fromFile(secretFile)
      try new JwtAuthenticationBackend(src.getLines().next(), httpClientBackend)
      finally src.close()
    case _ =>
      log.warn("JWT secret is not set")
      httpClientBackend
  }

  val engineApiClient = new HttpEngineApiClient(config, maybeAuthenticatedBackend)
  val httpApiClient = new HttpEcApiClient(config, maybeAuthenticatedBackend)

  val allChannels     = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
  val peerDatabase    = new PeerDatabaseImpl(config.network)
  val messageObserver = new MessageObserver()
  private val networkServer = NetworkServer(
    config,
    new HistoryReplier(httpApiClient, engineApiClient)(globalScheduler),
    peerDatabase,
    messageObserver,
    allChannels,
    new ConcurrentHashMap[Channel, PeerInfo]
  )

  val blockObserver = new BlocksObserverImpl(allChannels, messageObserver.blocks, config.blockSyncRequestTimeout)(blockObserverScheduler)

  override def close(): Unit = {
    log.info("Closing HTTP/Engine API")
    httpClientBackend.close()

    log.debug("Closing peer database L2")
    peerDatabase.close()

    log.info("Stopping network services L2")
    networkServer.shutdown()
    messageObserver.shutdown()

    log.info("Closing schedulers")
    blockObserverScheduler.shutdown()
    eluScheduler.shutdown()
  }
}
