package units.network

import com.google.common.primitives.{Bytes, Ints}
import com.wavesplatform.account.{Address, SeedKeyPair}
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.crypto
import com.wavesplatform.utils.ScorexLogging
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues}
import units.network.test.docker.{EcContainer, Networks, WavesNodeContainer}
import units.test.CustomMatchers

import java.nio.charset.StandardCharsets

trait BaseItTestSuite extends AnyFreeSpec with ScorexLogging with BeforeAndAfterAll with EitherValues with OptionValues with CustomMatchers {
  protected lazy val network = Networks.network

  protected lazy val ec1: EcContainer = new EcContainer(network, "ec-1", Networks.ipForNode(2)) // ipForNode(1) is assigned to Ryuk
  protected lazy val waves1: WavesNodeContainer = new WavesNodeContainer(
    network = network,
    number = 1,
    ip = Networks.ipForNode(3),
    keyPair = mkKeyPair("devnet-1".getBytes(StandardCharsets.UTF_8), 0),
    chainContract = Address.fromString("3FdaanzgX4roVgHevhq8L8q42E7EZL9XTQr", expectedChainId = Some('D'.toByte)).explicitGet(),
    ecEngineApiUrl = ec1.hostName
  )

  override def beforeAll(): Unit = {
    super.beforeAll()
    log.debug(s"Docker network name: ${network.getName}, id: ${network.getId}") // Force create network
    ec1.start()
    waves1.start()
    waves1.waitReady()
  }

  override protected def afterAll(): Unit = {
    waves1.stop()
    ec1.stop()
    super.afterAll()
  }

  protected def mkKeyPair(seed: Array[Byte], nonce: Int): SeedKeyPair =
    SeedKeyPair(crypto.secureHash(Bytes.concat(Ints.toByteArray(nonce), seed)))
}
