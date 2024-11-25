package units

import com.wavesplatform.account.AddressScheme
import com.wavesplatform.api.LoggingBackend
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.utils.ScorexLogging
import monix.execution.atomic.AtomicBoolean
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues}
import sttp.client3.{HttpClientSyncBackend, Identity, SttpBackend}
import units.client.HttpChainContractClient
import units.client.contract.HasConsensusLayerDappTxHelpers
import units.client.engine.model.BlockNumber
import units.docker.*
import units.docker.WavesNodeContainer.generateWavesGenesisConfig
import units.el.ElBridgeClient
import units.eth.Gwei
import units.test.{CustomMatchers, IntegrationTestEventually, TestEnvironment}

trait BaseDockerTestSuite
    extends AnyFreeSpec
    with ScorexLogging
    with BeforeAndAfterAll
    with Matchers
    with CustomMatchers
    with EitherValues
    with OptionValues
    with ReportingTestName
    with IntegrationTestEventually
    with Accounts
    with HasConsensusLayerDappTxHelpers {
  override val currentHitSource: ByteStr = ByteStr.empty
  protected val rewardAmount: Gwei       = Gwei.ofRawGwei(2_000_000_000L)

  protected lazy val network = Networks.network

  protected lazy val wavesGenesisConfigPath = generateWavesGenesisConfig()

  private implicit val httpClientBackend: SttpBackend[Identity, Any] = new LoggingBackend(HttpClientSyncBackend())

  protected lazy val ec1: EcContainer = {
    val constructor = TestEnvironment.ExecutionClient match {
      case "besu"    => new BesuContainer(_, _, _)
      case "geth"    => new GethContainer(_, _, _)
      case "op-geth" => new OpGethContainer(_, _, _)
      case x         => throw new RuntimeException(s"Unknown execution client: $x. Only 'geth' or 'besu' supported")
    }

    constructor(network, 1, Networks.ipForNode(2) /* ipForNode(1) is assigned to Ryuk */ )
  }

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

  protected def setupChain(): Unit = {
    log.info("Set script")
    waves1.api.broadcastAndWait(ChainContract.setScript())

    log.info("Setup chain contract")
    val genesisBlock = ec1.engineApi.getBlockByNumber(BlockNumber.Number(0)).explicitGet().getOrElse(fail("No EL genesis block"))
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
    // ec1.web3j // Hove no idea how to do this
  }

  override def beforeAll(): Unit = {
    BaseDockerTestSuite.init()
    super.beforeAll()
    log.debug(s"Docker network name: ${network.getName}, id: ${network.getId}") // Force create network

    startNodes()
    setupChain()
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
}
