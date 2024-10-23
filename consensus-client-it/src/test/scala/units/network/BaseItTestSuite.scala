package units.network

import com.google.common.primitives.{Bytes, Ints}
import com.wavesplatform.account.{Address, AddressScheme, SeedKeyPair}
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.crypto
import com.wavesplatform.utils.ScorexLogging
import monix.execution.atomic.AtomicBoolean
import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues}
import units.network.test.docker.{EcContainer, Networks, WavesNodeContainer}
import units.test.CustomMatchers

import java.nio.charset.StandardCharsets

trait BaseItTestSuite
    extends AnyFreeSpec
    with ScorexLogging
    with BeforeAndAfterAll
    with Matchers
    with CustomMatchers
    with EitherValues
    with OptionValues
    with Eventually {
  protected lazy val network = Networks.network

  protected lazy val ec1: EcContainer = new EcContainer(network, "ec-1", Networks.ipForNode(2)) // ipForNode(1) is assigned to Ryuk
  protected lazy val waves1: WavesNodeContainer = new WavesNodeContainer(
    network = network,
    number = 1,
    ip = Networks.ipForNode(3),
    baseSeed = "devnet-1",
    chainContractAddress = Address.fromString("3FdaanzgX4roVgHevhq8L8q42E7EZL9XTQr", expectedChainId = Some('D'.toByte)).explicitGet(),
    ecEngineApiUrl = s"http://${ec1.hostName}:${EcContainer.EnginePort}"
  )

  override def beforeAll(): Unit = {
    BaseItTestSuite.init()
    super.beforeAll()
    log.debug(s"Docker network name: ${network.getName}, id: ${network.getId}") // Force create network

    ec1.start()
    ec1.logPorts()

    waves1.start()
    waves1.waitReady()
    waves1.logPorts()
  }

  override protected def afterAll(): Unit = {
    waves1.stop()
    ec1.stop()
    super.afterAll()
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
