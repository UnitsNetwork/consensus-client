package units.network.test.docker

import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network.NetworkImpl
import org.testcontainers.utility.DockerImageName
import units.network.test.docker.BaseContainer.{ConfigsDir, DefaultLogsDir}
import units.network.test.docker.EcContainer.{EnginePort, RpcPort}

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

  val rpcPort    = new MappedPort(container, RpcPort)
  val enginePort = new MappedPort(container, EnginePort)
}

object EcContainer {
  val RpcPort    = 8545
  val EnginePort = 8551
}
