package units

import com.google.common.primitives.{Bytes, Ints}
import com.wavesplatform.account.{AddressScheme, KeyPair, SeedKeyPair}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.crypto
import com.wavesplatform.utils.ScorexLogging
import monix.execution.atomic.AtomicBoolean
import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues}
import org.web3j.crypto.Credentials
import units.client.contract.HasConsensusLayerDappTxHelpers
import units.client.engine.model.BlockNumber
import units.docker.{EcContainer, Networks, WavesNodeContainer}
import units.eth.{EthAddress, Gwei}
import units.test.CustomMatchers

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.DurationInt

trait BaseItTestSuite
    extends AnyFreeSpec
    with ScorexLogging
    with BeforeAndAfterAll
    with Matchers
    with CustomMatchers
    with EitherValues
    with OptionValues
    with Eventually
    with HasConsensusLayerDappTxHelpers {
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 30.seconds, interval = 1.second)

  override val currentHitSource: ByteStr     = ByteStr.empty
  override val chainContractAccount: KeyPair = mkKeyPair("devnet-1", 2)
  protected val rewardAmount: Gwei           = Gwei.ofRawGwei(2_000_000_000L)

  protected lazy val network = Networks.network

  protected lazy val ec1: EcContainer = new EcContainer(network, "ec-1", Networks.ipForNode(2)) // ipForNode(1) is assigned to Ryuk
  protected lazy val waves1: WavesNodeContainer = new WavesNodeContainer(
    network = network,
    number = 1,
    ip = Networks.ipForNode(3),
    baseSeed = "devnet-1",
    chainContractAddress = chainContractAddress,
    ecEngineApiUrl = s"http://${ec1.hostName}:${EcContainer.EnginePort}"
  )

  protected val miner1Account       = mkKeyPair("devnet-1", 0)
  protected val miner1RewardAddress = EthAddress.unsafeFrom("0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f9")

  protected val clRichAccount1 = mkKeyPair("devnet-0", 0)
  protected val clRichAccount2 = mkKeyPair("devnet-0", 1)

  protected val elRichAccount1 = Credentials.create("8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63")
  protected val elRichAccount2 = Credentials.create("ae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f")

  override def beforeAll(): Unit = {
    BaseItTestSuite.init()
    super.beforeAll()
    log.debug(s"Docker network name: ${network.getName}, id: ${network.getId}") // Force create network

    ec1.start()
    ec1.logPorts()

    waves1.start()
    waves1.waitReady()
    waves1.logPorts()

    setupNetwork()
  }

  override protected def afterAll(): Unit = {
    waves1.stop()
    ec1.stop()
    super.afterAll()
  }

  protected def setupNetwork(): Unit = {
    log.info("Set script")
    waves1.api.broadcastAndWait(chainContract.setScript())

    log.info("Setup chain contract")
    val genesisBlock = ec1.engineApi.getBlockByNumber(BlockNumber.Number(0)).explicitGet().getOrElse(fail("No EL genesis block"))
    waves1.api.broadcastAndWait(
      chainContract
        .setup(
          genesisBlock = genesisBlock,
          elMinerReward = rewardAmount.amount.longValue(),
          daoAddress = None,
          daoReward = 0,
          invoker = chainContractAccount
        )
    )

    log.info(s"Token id: ${waves1.chainContract.token}")

    log.info("Waves miner #1 join")
    val joinMiner1Result = waves1.api.broadcastAndWait(
      chainContract
        .join(
          minerAccount = miner1Account,
          elRewardAddress = miner1RewardAddress
        )
    )

    val epoch1Number = joinMiner1Result.height + 1
    log.info(s"Wait for #$epoch1Number epoch")
    waves1.api.waitForHeight(epoch1Number)
  }

  protected def mkKeyPair(seed: String, nonce: Int): SeedKeyPair =
    SeedKeyPair(crypto.secureHash(Bytes.concat(Ints.toByteArray(nonce), seed.getBytes(StandardCharsets.UTF_8))))
}

object BaseItTestSuite {
  private val initialized = AtomicBoolean(false)

  def init(): Unit =
    if (initialized.compareAndSet(expect = false, update = true))
      AddressScheme.current = new AddressScheme {
        override val chainId: Byte = 'D'.toByte
      }
}
