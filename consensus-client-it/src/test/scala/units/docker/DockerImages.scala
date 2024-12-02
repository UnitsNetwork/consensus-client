package units.docker

import org.testcontainers.utility.DockerImageName.parse
import units.test.TestEnvironment.WavesDockerImage

object DockerImages {
  val WavesNode           = parse(WavesDockerImage)
  val BesuExecutionClient = parse("hyperledger/besu:latest")
  val GethExecutionClient = parse("ethereum/client-go:stable")
}
