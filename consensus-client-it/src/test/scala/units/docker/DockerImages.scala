package units.docker

import org.testcontainers.utility.DockerImageName.parse
import units.test.TestEnvironment.WavesDockerImage

object DockerImages {
  val WavesNode             = parse(WavesDockerImage)
  val OpGethExecutionClient = parse("ghcr.io/unitsnetwork/op-geth:v1.101503.1-simulate-fixes")
}
