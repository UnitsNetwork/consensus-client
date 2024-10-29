package units.docker

import com.google.common.io.Files
import com.wavesplatform.account.Address
import com.wavesplatform.api.{LoggingBackend, NodeHttpApi}
import com.wavesplatform.common.utils.Base58
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network.NetworkImpl
import org.testcontainers.utility.DockerImageName
import sttp.client3.{HttpClientSyncBackend, UriContext}
import units.client.HttpChainContractClient
import units.docker.BaseContainer.{ConfigsDir, DefaultLogsDir}
import units.docker.WavesNodeContainer.ApiPort

import java.io.File
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.MapHasAsJava

class WavesNodeContainer(
    network: NetworkImpl,
    number: Int,
    ip: String,
    baseSeed: String,
    chainContractAddress: Address,
    ecEngineApiUrl: String
) extends BaseContainer(s"wavesnode-$number") {
  private val logFile = new File(s"$DefaultLogsDir/waves-$number.log")
  Files.touch(logFile)

  protected override val container = new GenericContainer(DockerImageName.parse(System.getProperty("cc.it.docker.image")))
    .withNetwork(network)
    .withExposedPorts(ApiPort)
    .withEnv(
      Map(
        "NODE_NUMBER"       -> s"$number",
        "WAVES_WALLET_SEED" -> Base58.encode(baseSeed.getBytes(StandardCharsets.UTF_8)),
        "JAVA_OPTS" -> List(
          s"-Dunits.defaults.chain-contract=$chainContractAddress",
          s"-Dunits.defaults.execution-client-address=$ecEngineApiUrl",
          "-Dlogback.file.level=TRACE",
          "-Dfile.encoding=UTF-8"
        ).mkString(" "),
        "WAVES_LOG_LEVEL" -> "TRACE", // STDOUT logs
        "WAVES_HEAP_SIZE" -> "1g"
      ).asJava
    )
    .withFileSystemBind(s"$ConfigsDir/wavesnode", "/etc/waves", BindMode.READ_ONLY)
    .withFileSystemBind(s"$ConfigsDir/ec-common", "/etc/secrets", BindMode.READ_ONLY)
    .withFileSystemBind(s"$logFile", "/var/log/waves/waves.log", BindMode.READ_WRITE)
    .withCreateContainerCmdModifier { cmd =>
      cmd
        .withName(s"${network.getName}-$hostName")
        .withHostName(hostName)
        .withIpv4Address(ip)
        .withStopTimeout(5) // Otherwise we don't have logs in the end
    }

  lazy val apiPort = container.getMappedPort(ApiPort)

  // TODO common from EcContainer
  private val httpClientBackend = new LoggingBackend(HttpClientSyncBackend())

  lazy val api = new NodeHttpApi(uri"http://${container.getHost}:$apiPort", httpClientBackend)

  lazy val chainContract = new HttpChainContractClient(api, chainContractAddress)

  override def stop(): Unit = {
    httpClientBackend.close()
    super.stop()
  }

  override def logPorts(): Unit = log.debug(s"External host: ${container.getHost}, api: $apiPort")
}

object WavesNodeContainer {
  val ApiPort = 6869
}
