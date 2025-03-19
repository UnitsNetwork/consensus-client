package units.test

import units.docker.EcContainer

import java.io.File
import java.nio.file.{Files, Path}

object TestEnvironment {
  val ConfigsDir: Path     = Path.of(System.getProperty("cc.it.configs.dir"))
  val DefaultLogsDir: Path = Path.of(System.getProperty("cc.it.logs.dir"))
  Files.createDirectories(DefaultLogsDir)

  val WavesDockerImage: String = System.getProperty("cc.it.docker.image")
  val ExecutionClient          = Option(System.getProperty("cc.it.ec"))

  val ContractsDir          = new File(sys.props("cc.it.contracts.dir"))
  val ContractAddressesFile = new File(s"${TestEnvironment.ContractsDir}/target/deployments/${EcContainer.ChainId}/.deploy")
}
