package units.network.test.docker

import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus.toFicusConfig
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network.NetworkImpl
import org.testcontainers.utility.DockerImageName
import sttp.client3.HttpClientSyncBackend
import units.ClientConfig
import units.client.engine.{HttpEngineApiClient, LoggedEngineApiClient}
import units.network.test.docker.BaseContainer.{ConfigsDir, DefaultLogsDir}
import units.network.test.docker.EcContainer.{EnginePort, RpcPort, mkConfig}

class EcContainer(network: NetworkImpl, hostName: String, ip: String) extends BaseContainer(hostName) {
  protected override val container = new GenericContainer(DockerImageName.parse("hyperledger/besu:latest"))
    .withNetwork(network)
    .withExposedPorts(RpcPort, EnginePort)
    .withEnv("LOG4J_CONFIGURATION_FILE", "/config/log4j2.xml")
    .withFileSystemBind(s"$ConfigsDir/ec-common/genesis.json", "/genesis.json", BindMode.READ_ONLY)
    .withFileSystemBind(s"$ConfigsDir/besu", "/config", BindMode.READ_ONLY)
    .withFileSystemBind(s"$ConfigsDir/besu/run-besu.sh", "/tmp/run.sh", BindMode.READ_ONLY)
    .withFileSystemBind(s"$ConfigsDir/ec-common/p2p-key-1.hex", "/etc/secrets/p2p-key", BindMode.READ_ONLY)
    .withFileSystemBind(s"$DefaultLogsDir", "/opt/besu/logs", BindMode.READ_WRITE)
    .withCreateContainerCmdModifier { cmd =>
      cmd
        .withName(s"${network.getName}-$hostName")
        .withHostName(hostName)
        .withIpv4Address(ip)
        .withEntrypoint("/tmp/run.sh")
    }

  lazy val rpcPort    = container.getMappedPort(RpcPort)
  lazy val enginePort = container.getMappedPort(EnginePort)

  private val httpClientBackend = HttpClientSyncBackend()
  lazy val engineApi            = new LoggedEngineApiClient(new HttpEngineApiClient(mkConfig(container.getHost, enginePort), httpClientBackend))

  override def stop(): Unit = {
    httpClientBackend.close()
    super.stop()
  }

  override def logPorts(): Unit = log.debug(s"External host: ${container.getHost}, rpc: $rpcPort, engine: $enginePort")
}

object EcContainer {
  val RpcPort    = 8545
  val EnginePort = 8551

  // TODO move
  private val baseConfig       = ConfigFactory.load(this.getClass.getClassLoader, "application.conf")
  private val baseClientConfig = baseConfig.as[ClientConfig]("waves.l2")

  private def mkConfig(host: String, port: Int): ClientConfig = baseClientConfig.copy(executionClientAddress = s"http://$host:$port")
}
