package units.docker

import com.google.common.io.Files
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import net.ceedubs.ficus.Ficus.toFicusConfig
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network.NetworkImpl
import org.testcontainers.utility.DockerImageName
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import sttp.client3.HttpClientSyncBackend
import units.ClientConfig
import units.client.engine.{HttpEngineApiClient, LoggedEngineApiClient}
import units.docker.BaseContainer.{ConfigsDir, DefaultLogsDir}
import units.docker.EcContainer.{EnginePort, RpcPort, mkConfig}
import units.el.ElBridgeClient
import units.eth.EthAddress
import units.http.OkHttpLogger

import java.io.File
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.CollectionHasAsScala

class EcContainer(network: NetworkImpl, number: Int, ip: String) extends BaseContainer(s"ec-$number") {
  private val logFile = new File(s"$DefaultLogsDir/besu-$number.log")
  Files.touch(logFile)

  protected override val container = new GenericContainer(DockerImageName.parse("hyperledger/besu:latest"))
    .withNetwork(network)
    .withExposedPorts(RpcPort, EnginePort)
    .withEnv(EcContainer.peersEnv, EcContainer.peersVal.mkString(","))
    .withEnv("LOG4J_CONFIGURATION_FILE", "/config/log4j2.xml")
    .withFileSystemBind(s"$ConfigsDir/ec-common/genesis.json", "/genesis.json", BindMode.READ_ONLY)
    .withFileSystemBind(s"$ConfigsDir/besu", "/config", BindMode.READ_ONLY)
    .withFileSystemBind(s"$ConfigsDir/besu/run-besu.sh", "/tmp/run.sh", BindMode.READ_ONLY)
    .withFileSystemBind(s"$ConfigsDir/ec-common/p2p-key-$number.hex", "/etc/secrets/p2p-key", BindMode.READ_ONLY)
    .withFileSystemBind(s"$logFile", "/opt/besu/logs/besu.log", BindMode.READ_WRITE)
    .withCreateContainerCmdModifier { cmd =>
      cmd
        .withName(s"${network.getName}-$hostName")
        .withHostName(hostName)
        .withIpv4Address(ip)
        .withEntrypoint("/tmp/run.sh")
        .withStopTimeout(5)
    }

  lazy val rpcPort    = container.getMappedPort(RpcPort)
  lazy val enginePort = container.getMappedPort(EnginePort)

  private val httpClientBackend = HttpClientSyncBackend()
  lazy val engineApi            = new LoggedEngineApiClient(new HttpEngineApiClient(mkConfig(container.getHost, enginePort), httpClientBackend))

  lazy val web3j = Web3j.build(
    new HttpService(
      s"http://${container.getHost}:$rpcPort",
      HttpService.getOkHttpClientBuilder
        .addInterceptor(OkHttpLogger)
        .build()
    )
  )

  lazy val elBridge = new ElBridgeClient(web3j, EthAddress.unsafeFrom("0x0000000000000000000000000000000000006a7e"))

  override def stop(): Unit = {
    web3j.shutdown()
    httpClientBackend.close()
    super.stop()
  }

  override def logPorts(): Unit = log.debug(s"External host: ${container.getHost}, rpc: $rpcPort, engine: $enginePort")
}

object EcContainer {
  val RpcPort    = 8545
  val EnginePort = 8551

  // TODO move
  private val baseConfig = ConfigFactory.load(this.getClass.getClassLoader, "application.conf")
  private def mkConfig(host: String, port: Int): ClientConfig = baseConfig
    .getConfig("units.defaults")
    .withValue("chain-contract", ConfigValueFactory.fromAnyRef("")) // Doesn't matter for HttpEngineApiClient
    .withValue("execution-client-address", ConfigValueFactory.fromAnyRef(s"http://$host:$port"))
    .resolve()
    .as[ClientConfig]

  val (peersEnv, peersVal) = {
    val file = new File(s"$ConfigsDir/ec-common/peers.env")
    Files
      .readLines(file, StandardCharsets.UTF_8)
      .asScala
      .mkString("")
      .split('=') match {
      case Array(peersEnv, peersVal, _*) => (peersEnv, peersVal.split(',').map(_.trim))
      case xs                            => throw new RuntimeException(s"Wrong $file content: ${xs.mkString(", ")}")
    }
  }
}
