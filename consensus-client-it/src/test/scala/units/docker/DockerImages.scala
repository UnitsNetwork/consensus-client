package units.docker

import org.testcontainers.utility.DockerImageName.parse
import units.test.TestEnvironment.WavesDockerImage

object DockerImages {
  val WavesNode             = parse(WavesDockerImage)
  val OpGethExecutionClient = parse("us-docker.pkg.dev/oplabs-tools-artifacts/images/op-geth:v1.101503.1")
}
