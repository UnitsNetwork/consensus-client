package units.network.test.docker

import com.github.dockerjava.api.model.{Binds, HostConfig}
import org.testcontainers.containers.Network.NetworkImpl
import org.testcontainers.utility.DockerImageName
import units.network.test.docker.BaseContainer.{ConfigsDir, DefaultLogsDir}

import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.util.chaining.scalaUtilChainingOps

class EcContainer(network: NetworkImpl, hostName: String, ip: String) extends BaseContainer(hostName) {
  val rpcPort    = Ports.nextFreePort()
  val enginePort = Ports.nextFreePort()

  protected override val container = new GenericContainer(DockerImageName.parse("hyperledger/besu:latest"))
    .withNetwork(network)
    .withEnv("LOG4J_CONFIGURATION_FILE", "/config/log4j2.xml")
    // .withExposedPorts(8545) // Doesn't work in testcontainers
    .withCreateContainerCmdModifier { cmd =>
      cmd
        .withName(s"${network.getName}-$hostName")
        .withEntrypoint("/tmp/run.sh")
        .withHostName(hostName)
        .withIpv4Address(ip)
        .withPortSpecs(
          "127.0.0.1:28551:8545", // RPC
          "127.0.0.1:28551:8551"  // Engine
        )
        .withHostConfig(
          HostConfig
            .newHostConfig()
            .withBinds(
              Binds.fromPrimitive(
                Array(
                  s"$ConfigsDir/ec-common/genesis.json:/genesis.json:ro",
                  s"$ConfigsDir/besu:/config:ro",
                  s"$ConfigsDir/besu/run-besu.sh:/tmp/run.sh:ro",
                  s"$ConfigsDir/ec-common/p2p-key-1.hex:/etc/secrets/p2p-key:ro",
                  s"$DefaultLogsDir:/opt/besu/logs:rw"
                )
              )
            )
        )
    }
//    .tap {
//      _.setPortBindings(
//        List(
//          s"127.0.0.1:$rpcPort:8545",
//          s"127.0.0.1:$enginePort:8551"
//        ).asJava
//      )
//    }
}
