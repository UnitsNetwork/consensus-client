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

class OpGethContainer(network: NetworkImpl, number: Int, ip: String)(implicit httpClientBackend: SttpBackend[Identity, Any])
    extends EcContainer(number) {

  protected override val container = new GenericContainer(DockerImages.OpGethExecutionClient)
    .withNetwork(network)
    .withExposedPorts(RpcPort, EnginePort)
    .withEnv("NODE_NUMBER", number.toString)
    .withEnv("GETH_NETWORKID", "1337")
    .withFileSystemBind(s"$ConfigsDir/ec-common", "/etc/secrets", BindMode.READ_ONLY)
    .withFileSystemBind(s"$ConfigsDir/op-geth/run-op-geth.sh", "/tmp/run.sh", BindMode.READ_ONLY)
    .withFileSystemBind(s"$logFile", "/root/logs/op-geth.log", BindMode.READ_WRITE)
    .withCreateContainerCmdModifier { cmd =>
      cmd
        .withName(s"${network.getName}-$hostName")
        .withHostName(hostName)
        .withIpv4Address(ip)
        //        .withEnv("GETH_NETWORKID", "1337")
        //        .withEnv("NODE_NUMBER", number.toString)
        .withEntrypoint("/bin/sh", "-c")
        .withCmd(
          s"""if [ ! -d /root/.ethereum/geth/chaindata ] ; then
  geth init /etc/secrets/genesis.json
else
  echo geth already initialized
fi
exec geth --networkid=1337 \\
--syncmode=full --nat=extip:${ip} --http --http.addr=0.0.0.0 --http.vhosts=* --http.api=eth,web3,txpool,net,debug,engine --http.corsdomain=* --ws --ws.addr=0.0.0.0 --ws.api=eth,web3,txpool,net,debug --ws.rpcprefix=/ --ws.origins='*' --authrpc.addr=0.0.0.0 --authrpc.vhosts='*' --authrpc.jwtsecret=/etc/secrets/jwtsecret.hex --nodekey=/etc/secrets/p2p-key-${number.toString}.hex --log.file=/root/logs/op-geth.log --verbosity=5 --log.format=terminal --log.rotate --log.compress"""
        )
        .withStopTimeout(5)
    }

  lazy val jwtSecretKey = {
    val src = Source.fromFile(s"$ConfigsDir/ec-common/jwtsecret.hex")
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
