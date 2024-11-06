package units.docker

import org.testcontainers.utility.DockerImageName.parse
import units.test.TestEnvironment.WavesDockerImage

object DockerImages {
  val WavesNode       = parse(WavesDockerImage)
  val ExecutionClient = parse("hyperledger/besu:latest")
}
