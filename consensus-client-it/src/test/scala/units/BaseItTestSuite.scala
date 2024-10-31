package units

import com.google.common.primitives.{Bytes, Ints}
import com.typesafe.config.ConfigFactory
import com.wavesplatform.account.{AddressScheme, KeyPair, SeedKeyPair}
import com.wavesplatform.api.HasRetry
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.utils.ScorexLogging
import com.wavesplatform.{GenesisBlockGenerator, crypto}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues}
import org.web3j.crypto.Credentials
import units.client.contract.HasConsensusLayerDappTxHelpers
import units.docker.BaseContainer.ConfigsDir
import units.docker.Networks
import units.eth.{EthAddress, Gwei}
import units.test.CustomMatchers

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.concurrent.duration.DurationInt

trait BaseItTestSuite
    extends AnyFreeSpec
    with ScorexLogging
    with BeforeAndAfterAll
    with Matchers
    with CustomMatchers
    with EitherValues
    with OptionValues
    with HasRetry
    with HasConsensusLayerDappTxHelpers {
  override val currentHitSource: ByteStr     = ByteStr.empty
  override val chainContractAccount: KeyPair = mkKeyPair("devnet-1", 2)
  protected val rewardAmount: Gwei           = Gwei.ofRawGwei(2_000_000_000L)

  protected lazy val network = Networks.network

  protected val miner1Account       = mkKeyPair("devnet-1", 0)
  protected val miner1RewardAddress = EthAddress.unsafeFrom("0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f9")

  protected val miner2Account       = mkKeyPair("devnet-2", 0)
  protected val miner2RewardAddress = EthAddress.unsafeFrom("0xcf0b9e13fdd593f4ca26d36afcaa44dd3fdccbed")

  protected val clRichAccount1 = mkKeyPair("devnet-0", 0)
  protected val clRichAccount2 = mkKeyPair("devnet-0", 1)

  protected val elRichAccount1 = Credentials.create("8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63")
  protected val elRichAccount2 = Credentials.create("ae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f")

  protected def startNodes(): Unit

  protected def stopNodes(): Unit

  protected def setupNetwork(): Unit

  override def beforeAll(): Unit = {
    BaseItTestSuite.init()
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

  protected def mkKeyPair(seed: String, nonce: Int): SeedKeyPair =
    SeedKeyPair(crypto.secureHash(Bytes.concat(Ints.toByteArray(nonce), seed.getBytes(StandardCharsets.UTF_8))))
}

object BaseItTestSuite {
  private var initialized = false

  def init(): Unit = synchronized {
    if (!initialized) {
      AddressScheme.current = new AddressScheme {
        override val chainId: Byte = 'D'.toByte
      }

      val templateFile = ConfigsDir.resolve("wavesnode/genesis-template.conf").toAbsolutePath
      val genesisFile  = ConfigsDir.resolve("wavesnode/genesis.conf").toAbsolutePath

      val origConfig = ConfigFactory.parseFile(templateFile.toFile)
      val gap        = 1.minute // To force node mining at start, otherwise it schedules
      val overrides = ConfigFactory.parseString(
        s"""genesis-generator {
           |  timestamp = ${System.currentTimeMillis() - gap.toMillis}
           |}""".stripMargin
      )

      val genesisSettings = GenesisBlockGenerator.parseSettings(overrides.withFallback(origConfig))

      val origOut = System.out
      System.setOut(new PrintStream({ (_: Int) => }))
      val config = GenesisBlockGenerator.createConfig(genesisSettings)
      System.setOut(origOut)

      Files.writeString(genesisFile, config)

      initialized = true
    }
  }
}
