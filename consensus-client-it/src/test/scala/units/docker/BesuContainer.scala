package units.docker

import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network.NetworkImpl
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import sttp.client3.{Identity, SttpBackend}
import units.client.JsonRpcClient
import units.client.engine.{EngineApiClient, HttpEngineApiClient, LoggedEngineApiClient}
import units.docker.EcContainer.{EnginePort, RpcPort}
import units.http.OkHttpLogger
import units.test.TestEnvironment.ConfigsDir

import scala.concurrent.duration.DurationInt

class BesuContainer(network: NetworkImpl, number: Int, ip: String)(implicit httpClientBackend: SttpBackend[Identity, Any])
    extends EcContainer(number) {
  protected override val container = new GenericContainer(DockerImages.BesuExecutionClient)
    .withNetwork(network)
    .withExposedPorts(RpcPort, EnginePort)
    .withEnv("LOG4J_CONFIGURATION_FILE", "/config/log4j2.xml")
    .withEnv("ROOT_LOG_FILE_LEVEL", "TRACE")
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

  override lazy val engineApi: EngineApiClient = new LoggedEngineApiClient(
    new HttpEngineApiClient(
      JsonRpcClient.Config(apiUrl = s"http://${container.getHost}:$enginePort", apiRequestRetries = 5, apiRequestRetryWaitTime = 1.second),
      httpClientBackend
    )
  )

  override lazy val web3j = Web3j.build(
    new HttpService(
      s"http://${container.getHost}:$rpcPort",
      HttpService.getOkHttpClientBuilder
        .addInterceptor(OkHttpLogger)
        .build()
    )
  )
}
