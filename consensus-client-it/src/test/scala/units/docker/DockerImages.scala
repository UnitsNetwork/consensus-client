package units.docker

import org.testcontainers.utility.DockerImageName.parse
import units.test.TestEnvironment.WavesDockerImage

object DockerImages {
  val WavesNode             = parse(WavesDockerImage)
  val OpGethExecutionClient = parse("op-geth-local")// parse("ghcr.io/unitsnetwork/op-geth:sim-withdrawals")
}
