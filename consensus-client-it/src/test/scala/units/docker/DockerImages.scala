package units.docker

import org.testcontainers.utility.DockerImageName.parse
import units.test.TestEnvironment.WavesDockerImage

object DockerImages {
  val WavesNode             = parse(WavesDockerImage)
  val OpGethExecutionClient = parse("ghcr.io/unitsnetwork/op-geth:1.15.11-race-detector")
}
