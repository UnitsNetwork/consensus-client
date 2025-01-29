package units

import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import com.wavesplatform.block.{Block, MicroBlock}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.events.BlockchainUpdateTriggers
import com.wavesplatform.extensions.{Extension, Context as ExtensionContext}
import com.wavesplatform.state.{Blockchain, StateSnapshot}
import io.netty.channel.group.DefaultChannelGroup
import monix.execution.{CancelableFuture, Scheduler}
import pureconfig.ConfigSource
import units.ConsensusClient.ChainHandler
import units.client.engine.EngineApiClient
import units.network.*

import scala.concurrent.Future
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.Try

class ConsensusClient(context: ExtensionContext) extends StrictLogging with Extension with BlockchainUpdateTriggers {
  import scala.concurrent.ExecutionContext.Implicits.global

  private def requireUnique[A](configs: Iterable[ClientConfig], key: ClientConfig => A, keyName: String): Unit = {
    val duplicateKeys = configs.groupBy(key).collect { case (k, confs) if confs.size > 1 => k -> confs.size }
    require(duplicateKeys.isEmpty, s"The following $keyName were used several times in config: ${duplicateKeys.mkString(",")}")
  }

  private val chainHandlers: Seq[ChainHandler] = {
    val defaultConfig = context.settings.config.getConfig("units.defaults")

    def load(cfg: Config): ClientConfig =
      ConfigSource.fromConfig(cfg.withFallback(defaultConfig).resolve()).loadOrThrow[ClientConfig]

    val legacyChainConfig =
      Try(context.settings.config.getConfig("waves.l2")).toOption.map(load).tapEach { _ =>
        logger.info("Consensus client settings at waves.l2 path have been deprecated, please update your config file")
      }

    val newChainConfigs = context.settings.config
      .getConfigList("units.chains")
      .asScala
      .map(load)

    val allChainConfigs = legacyChainConfig ++ newChainConfigs

    requireUnique(allChainConfigs, _.chainContract, "chain contract addresses")
    requireUnique(allChainConfigs, _.executionClientAddress, "execution client addresses")

    allChainConfigs.map(cfg => new ConsensusClient.ChainHandler(context, new ConsensusClientDependencies(cfg))).toVector
  }

  override def start(): Unit = {}

  def shutdown(): Future[Unit] = Future.sequence(chainHandlers.map(h => Future(h.close()))).map(_ => ())

  override def onProcessBlock(
      block: Block,
      snapshot: StateSnapshot,
      reward: Option[Long],
      hitSource: ByteStr,
      blockchainBeforeWithReward: Blockchain
  ): Unit = chainHandlers.foreach(_.elu.consensusLayerChanged())

  override def onProcessMicroBlock(
      microBlock: MicroBlock,
      snapshot: StateSnapshot,
      blockchainBeforeWithReward: Blockchain,
      totalBlockId: ByteStr,
      totalTransactionsRoot: ByteStr
  ): Unit = chainHandlers.foreach(_.elu.consensusLayerChanged())

  override def onRollback(blockchainBefore: Blockchain, toBlockId: ByteStr, toHeight: Int): Unit = {}

  override def onMicroBlockRollback(blockchainBefore: Blockchain, toBlockId: ByteStr): Unit = {}
}

object ConsensusClient {
  class ChainHandler(
      context: ExtensionContext,
      config: ClientConfig,
      engineApiClient: EngineApiClient,
      blockObserver: BlocksObserver,
      allChannels: DefaultChannelGroup,
      globalScheduler: Scheduler,
      eluScheduler: Scheduler,
      ownedResources: AutoCloseable
  ) extends AutoCloseable {
    def this(context: ExtensionContext, deps: ConsensusClientDependencies) =
      this(context, deps.config, deps.engineApiClient, deps.blockObserver, deps.allChannels, deps.globalScheduler, deps.eluScheduler, deps)

    val elu = new ELUpdater(
      engineApiClient,
      context.blockchain,
      context.utx,
      allChannels,
      config,
      context.time,
      context.wallet,
      context.settings.blockchainSettings.functionalitySettings.unitsRegistryAddressParsed.explicitGet(),
      blockObserver.loadBlock,
      context.broadcastTransaction,
      eluScheduler,
      globalScheduler
    )

    private val blocksStreamCancelable: CancelableFuture[Unit] =
      blockObserver.getBlockStream.foreach { case (ch, block) => elu.executionBlockReceived(block, ch) }(globalScheduler)

    override def close(): Unit = {
      blocksStreamCancelable.cancel()
      ownedResources.close()
    }
  }
}
