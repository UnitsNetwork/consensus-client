package units

import com.wavesplatform.network.{PeerDatabaseImpl, PeerInfo}
import com.wavesplatform.utils.{LoggerFacade, Schedulers}
import io.netty.channel.Channel
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import org.slf4j.LoggerFactory
import sttp.client3.HttpClientSyncBackend
import units.client.JwtAuthenticationBackend
import units.client.engine.{HttpEngineApiClient, LoggedEngineApiClient}
import units.network.{BlocksObserverImpl, HistoryReplier, MessageObserver, NetworkServer}

import java.util.concurrent.ConcurrentHashMap
import scala.io.Source

// A helper to create ConsensusClient due to Scala secondary constructors limitations
class ConsensusClientDependencies(val config: ClientConfig) extends AutoCloseable {
  protected lazy val log: LoggerFacade = LoggerFacade(LoggerFactory.getLogger(classOf[ConsensusClient]))

  private val blockObserverScheduler =
    Schedulers.singleThread(s"block-observer-${config.chainContract}", reporter = { e => log.warn("Error in BlockObserver", e) })
  val globalScheduler: Scheduler = monix.execution.Scheduler.global
    .withUncaughtExceptionReporter((ex: Throwable) => log.warn("Error in Global Scheduler", ex))
  val eluScheduler: SchedulerService =
    Scheduler.singleThread(s"el-updater-${config.chainContract}", reporter = { e => log.warn("Exception in ELUpdater", e) })

  private val httpClientBackend = HttpClientSyncBackend()
  private val maybeAuthenticatedBackend = config.jwtSecretFile match {
    case Some(secretFile) =>
      val src = Source.fromFile(secretFile)
      try new JwtAuthenticationBackend(src.getLines().next(), httpClientBackend)
      finally src.close()
    case _ =>
      log.warn("JWT secret is not set")
      httpClientBackend
  }

  val engineApiClient = new LoggedEngineApiClient(new HttpEngineApiClient(config.jsonRpcClient, maybeAuthenticatedBackend))

  val allChannels     = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
  val peerDatabase    = new PeerDatabaseImpl(config.network)
  val messageObserver = new MessageObserver()
  private val networkServer = NetworkServer(
    config,
    new HistoryReplier(engineApiClient)(globalScheduler),
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
