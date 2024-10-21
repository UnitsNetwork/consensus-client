package units.network.test.docker

import com.wavesplatform.account.Address
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.wavesj.Node
import org.apache.http.client.config.{CookieSpecs, RequestConfig}
import org.apache.http.impl.client.HttpClients
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network.NetworkImpl
import org.testcontainers.utility.DockerImageName
import units.network.test.docker.BaseContainer.{ConfigsDir, DefaultLogsDir}
import units.network.test.docker.WavesNodeContainer.ApiPort

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.MapHasAsJava

class WavesNodeContainer(
    network: NetworkImpl,
    number: Int,
    ip: String,
    baseSeed: String,
    chainContract: Address,
    ecEngineApiUrl: String
) extends BaseContainer(s"wavesnode-$number") {
  protected override val container = new GenericContainer(DockerImageName.parse(System.getProperty("cc.it.docker.image")))
    .withNetwork(network)
    .withExposedPorts(ApiPort)
    .withEnv(
      Map(
        "NODE_NUMBER"       -> s"$number",
        "WAVES_WALLET_SEED" -> Base58.encode(baseSeed.getBytes(StandardCharsets.UTF_8)),
        "JAVA_OPTS" -> List(
          s"-Dwaves.l2.chain-contract=$chainContract",
          s"-Dwaves.l2.execution-client-address=$ecEngineApiUrl",
          "-Dlogback.file.level=TRACE",
          "-Dfile.encoding=UTF-8"
        ).mkString(" "),
        "WAVES_LOG_LEVEL" -> "TRACE", // STDOUT logs
        "WAVES_HEAP_SIZE" -> "1g"
      ).asJava
    )
    .withFileSystemBind(s"$ConfigsDir/wavesnode", "/etc/waves", BindMode.READ_ONLY)
    .withFileSystemBind(s"$ConfigsDir/ec-common", "/etc/secrets", BindMode.READ_ONLY)
    .withFileSystemBind(s"$DefaultLogsDir", "/var/log/waves", BindMode.READ_WRITE)
    .withCreateContainerCmdModifier { cmd =>
      cmd
        .withName(s"${network.getName}-$hostName")
        .withHostName(hostName)
        .withIpv4Address(ip)
        .withStopTimeout(5) // Otherwise we don't have logs in the end
    }

  lazy val apiPort = container.getMappedPort(ApiPort)

  private lazy val httpClient = HttpClients.custom
    .setDefaultRequestConfig(
      RequestConfig.custom
        .setSocketTimeout(60000)
        .setConnectTimeout(60000)
        .setConnectionRequestTimeout(60000)
        .setCookieSpec(CookieSpecs.STANDARD)
        .build
    )
    .build

  lazy val api = new Node(s"http://${container.getHost}:$apiPort", httpClient)

  override def stop(): Unit = {
    httpClient.close()
    super.stop()
  }
}

object WavesNodeContainer {
  val ApiPort = 6869
}
