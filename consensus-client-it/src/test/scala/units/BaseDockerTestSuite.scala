package units

import com.typesafe.config.ConfigFactory
import com.wavesplatform.GenesisBlockGenerator
import com.wavesplatform.account.AddressScheme
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.utils.ScorexLogging
import monix.execution.atomic.AtomicBoolean
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues}
import units.BaseDockerTestSuite.generateWavesGenesisConfig
import units.client.contract.HasConsensusLayerDappTxHelpers
import units.docker.Networks
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

  protected def startNodes(): Unit
  protected def stopNodes(): Unit
  protected def setupNetwork(): Unit

  override def beforeAll(): Unit = {
    BaseDockerTestSuite.init()
    super.beforeAll()
    log.debug(s"Docker network name: ${network.getName}, id: ${network.getId}") // Force create network

    startNodes()
    setupNetwork()
  }

  override protected def afterAll(): Unit = {
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
