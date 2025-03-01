package units.docker

import okhttp3.Interceptor
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network.NetworkImpl
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtJson}
import sttp.client3.{Identity, SttpBackend}
import units.client.JwtAuthenticationBackend
import units.client.engine.{EngineApiClient, HttpEngineApiClient, LoggedEngineApiClient}
import units.docker.EcContainer.{EnginePort, RpcPort}
import units.http.OkHttpLogger
import units.test.TestEnvironment.ConfigsDir

import java.time.Clock
import scala.io.Source

class GethContainer(network: NetworkImpl, number: Int, ip: String)(implicit httpClientBackend: SttpBackend[Identity, Any])
    extends EcContainer(number) {
  protected override val container = new GenericContainer(DockerImages.GethExecutionClient)
    .withNetwork(network)
    .withExposedPorts(RpcPort, EnginePort)
    .withEnv("NODE_NUMBER", s"$number")
    .withFileSystemBind(s"$ConfigsDir/ec-common", "/etc/secrets", BindMode.READ_ONLY)
    .withFileSystemBind(s"$ConfigsDir/geth/run-geth.sh", "/tmp/run.sh", BindMode.READ_ONLY)
    .withFileSystemBind(s"$logFile", "/root/logs/log", BindMode.READ_WRITE)
    .withCreateContainerCmdModifier { cmd =>
      cmd
        .withName(s"${network.getName}-$hostName")
        .withHostName(hostName)
        .withIpv4Address(ip)
        .withEntrypoint("/tmp/run.sh")
        .withStopTimeout(5)
    }

  lazy val jwtSecretKey = {
    val src = Source.fromFile(s"$ConfigsDir/ec-common/jwt-secret-$number.hex")
    try src.getLines().next()
    finally src.close()
  }

  override lazy val engineApi: EngineApiClient = new LoggedEngineApiClient(
    new HttpEngineApiClient(
      engineApiConfig,
      new JwtAuthenticationBackend(jwtSecretKey, httpClientBackend)
    )
  )

  override lazy val web3j = Web3j.build(
    new HttpService(
      s"http://${container.getHost}:$rpcPort",
      HttpService.getOkHttpClientBuilder
        .addInterceptor { (chain: Interceptor.Chain) =>
          val orig     = chain.request()
          val jwtToken = JwtJson.encode(JwtClaim().issuedNow(Clock.systemUTC), jwtSecretKey, JwtAlgorithm.HS256)
          val request = orig
            .newBuilder()
            .header("Authorization", s"Bearer $jwtToken")
            .build()

          chain.proceed(request)
        }
        .addInterceptor(OkHttpLogger)
        .build()
    )
  )
}
