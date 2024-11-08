package units

import com.typesafe.config.ConfigFactory
import com.wavesplatform.GenesisBlockGenerator
import com.wavesplatform.account.AddressScheme
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.utils.ScorexLogging
import monix.execution.atomic.AtomicBoolean
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues}
import sttp.client3.{HttpClientSyncBackend, Identity, SttpBackend}
import units.BaseDockerTestSuite.generateWavesGenesisConfig
import units.client.HttpChainContractClient
import units.client.contract.HasConsensusLayerDappTxHelpers
import units.client.engine.model.BlockNumber
import units.docker.{EcContainer, Networks, WavesNodeContainer}
import units.el.ElBridgeClient
import units.eth.Gwei
import units.test.TestEnvironment.*
import units.test.{CustomMatchers, HasRetry}

import java.io.PrintStream
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

trait BaseDockerTestSuite
    extends AnyFreeSpec
    with ScorexLogging
    with BeforeAndAfterAll
    with Matchers
    with CustomMatchers
    with EitherValues
    with OptionValues
    with ReportingTestName
    with HasRetry
    with Accounts
    with HasConsensusLayerDappTxHelpers {
  override val currentHitSource: ByteStr = ByteStr.empty
  protected val rewardAmount: Gwei       = Gwei.ofRawGwei(2_000_000_000L)

  protected lazy val network = Networks.network

  protected lazy val wavesGenesisConfigPath = generateWavesGenesisConfig()

  private implicit val httpClientBackend: SttpBackend[Identity, Any] = HttpClientSyncBackend()

  protected lazy val ec1: EcContainer = new EcContainer(
    network = network,
    number = 1,
    ip = Networks.ipForNode(2) // ipForNode(1) is assigned to Ryuk
  )

  protected lazy val waves1: WavesNodeContainer = new WavesNodeContainer(
    network = network,
    number = 1,
    ip = Networks.ipForNode(3),
    baseSeed = "devnet-1",
    chainContractAddress = chainContractAddress,
    ecEngineApiUrl = ec1.engineApiDockerUrl,
    genesisConfigPath = wavesGenesisConfigPath
  )

  protected lazy val chainContract = new HttpChainContractClient(waves1.api, chainContractAddress)
  protected lazy val elBridge      = new ElBridgeClient(ec1.web3j, elBridgeAddress)

  protected def startNodes(): Unit = {
    ec1.start()
    ec1.logPorts()

    waves1.start()
    waves1.waitReady()
    waves1.logPorts()
  }

  protected def stopNodes(): Unit = {
    waves1.stop()
    ec1.stop()
  }

  protected def setupNetwork(): Unit = {
    log.info("Set script")
    waves1.api.broadcastAndWait(ChainContract.setScript())

    log.info("Setup chain contract")
    val genesisBlock = ec1.engineApi.getBlockByNumber(BlockNumber.Number(0)).explicitGet().getOrElse(failRetry("No EL genesis block"))
    waves1.api.broadcastAndWait(
      ChainContract.setup(
        genesisBlock = genesisBlock,
        elMinerReward = rewardAmount.amount.longValue(),
        daoAddress = None,
        daoReward = 0,
        invoker = chainContractAccount
      )
    )
    log.info(s"Token id: ${chainContract.token}")

    log.info("EL miner #1 join")
    val joinMiner1Result = waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = miner11Account,
        elRewardAddress = miner11RewardAddress
      )
    )

    val epoch1Number = joinMiner1Result.height + 1
    log.info(s"Wait for #$epoch1Number epoch")
    waves1.api.waitForHeight(epoch1Number)
  }

  override protected def step(text: String): Unit = {
    super.step(text)
    waves1.api.print(text)
  }

  override def beforeAll(): Unit = {
    BaseDockerTestSuite.init()
    super.beforeAll()
    log.debug(s"Docker network name: ${network.getName}, id: ${network.getId}") // Force create network

    startNodes()
    setupNetwork()
  }

  override protected def afterAll(): Unit = {
    httpClientBackend.close()

    stopNodes()
    network.close()
    super.afterAll()
  }
}

object BaseDockerTestSuite {
  private val initialized = AtomicBoolean(false)

  def init(): Unit =
    if (initialized.compareAndSet(expect = false, update = true))
      AddressScheme.current = new AddressScheme {
        override val chainId: Byte = 'D'.toByte
      }

  def generateWavesGenesisConfig(): Path = {
    val templateFile = ConfigsDir.resolve("wavesnode/genesis-template.conf").toAbsolutePath

    val origConfig = ConfigFactory.parseFile(templateFile.toFile)
    val gap        = 1.minute // To force node mining at start, otherwise it schedules
    val overrides = ConfigFactory.parseString(
      s"""genesis-generator {
         |  timestamp = ${System.currentTimeMillis() - gap.toMillis}
         |}""".stripMargin
    )

    val genesisSettings = GenesisBlockGenerator.parseSettings(overrides.withFallback(origConfig))

    val origOut = System.out
    System.setOut(new PrintStream({ (_: Int) => })) // We don't use System.out in tests, so it should not be an issue
    val config = GenesisBlockGenerator.createConfig(genesisSettings)
    System.setOut(origOut)

    val dest = DefaultLogsDir.resolve("genesis.conf").toAbsolutePath
    Files.writeString(dest, config)
    dest
  }
}
