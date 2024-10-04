package units.network.test.docker

import com.github.dockerjava.api.model.{Binds, HostConfig}
import com.wavesplatform.account.{Address, SeedKeyPair}
import com.wavesplatform.common.utils.Base58
import org.testcontainers.containers.Network.NetworkImpl
import org.testcontainers.utility.DockerImageName
import units.network.test.docker.BaseContainer.{ConfigsDir, DefaultLogsDir}

import scala.jdk.CollectionConverters.MapHasAsJava

class WavesNodeContainer(
    network: NetworkImpl,
    number: Int,
    ip: String,
    keyPair: SeedKeyPair,
    chainContract: Address,
    ecEngineApiUrl: String
) extends BaseContainer(s"wavesnode-$number") {
  protected override val container = new GenericContainer(DockerImageName.parse(System.getProperty("cc.it.docker.image")))
    .withNetwork(network)
    .withEnv(
      Map(
        "NODE_NUMBER"       -> s"$number",
        "WAVES_WALLET_SEED" -> Base58.encode(keyPair.seed),
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
    .withCreateContainerCmdModifier { cmd =>
      cmd
        .withName(s"${network.getName}-$hostName")
        .withHostName(hostName)
        .withIpv4Address(ip)
        .withPortSpecs(
          s"127.0.0.1:${number}6869:6869" // HTTP API
        )
        .withStopTimeout(5) // Otherwise we don't have logs in the end
        .withHostConfig(
          HostConfig
            .newHostConfig()
            .withBinds(
              Binds.fromPrimitive(
                Array(
                  s"$ConfigsDir/wavesnode:/etc/waves:ro",
                  s"$ConfigsDir/ec-common:/etc/secrets:ro",
                  s"$DefaultLogsDir:/var/log/waves:rw"
                )
              )
            )
        )
    }

  override def stop(): Unit = {
    // container.stop() kills and removes the container, and we lose logs. stopContainerCmd stops gracefully.
    container.getDockerClient.stopContainerCmd(container.getContainerId).exec()
  }
}
