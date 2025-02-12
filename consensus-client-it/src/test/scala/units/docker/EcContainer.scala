package units.docker

import com.google.common.io.Files
import org.web3j.protocol.Web3j
import units.client.JsonRpcClient
import units.client.engine.EngineApiClient
import units.docker.EcContainer.{EnginePort, RpcPort}
import units.test.TestEnvironment.*

import java.io.File
import scala.concurrent.duration.DurationInt

abstract class EcContainer(number: Int) extends BaseContainer(s"ec-$number") {
  protected val logFile = new File(s"$DefaultLogsDir/ec-$number.log")
  Files.touch(logFile)

  lazy val rpcPort    = container.getMappedPort(RpcPort)
  lazy val enginePort = container.getMappedPort(EnginePort)

  lazy val engineApiDockerUrl = s"http://$hostName:${EcContainer.EnginePort}"
  lazy val engineApiConfig = JsonRpcClient.Config(
    apiUrl = s"http://${container.getHost}:$enginePort",
    apiRequestRetries = 5,
    apiRequestRetryWaitTime = 1.second
  )

  def engineApi: EngineApiClient
  def web3j: Web3j

  override def stop(): Unit = {
    web3j.shutdown()
    super.stop()
  }

  override def logPorts(): Unit = log.debug(s"External host: ${container.getHost}, rpc: $rpcPort, engine: $enginePort")
}

object EcContainer {
  val RpcPort    = 8545
  val EnginePort = 8551
  val ChainId    = 1337L // from eth-genesis-template.json
}
