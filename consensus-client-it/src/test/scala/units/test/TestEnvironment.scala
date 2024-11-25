package units.test

import java.nio.file.{Files, Path}

object TestEnvironment {
  val ConfigsDir: Path     = Path.of(System.getProperty("cc.it.configs.dir"))
  val DefaultLogsDir: Path = Path.of(System.getProperty("cc.it.logs.dir"))
  Files.createDirectories(DefaultLogsDir)

  val WavesDockerImage: String = System.getProperty("cc.it.docker.image")
  val ExecutionClient: String  = System.getProperty("cc.it.ec", "op-geth") // besu | geth | op-geth
}
