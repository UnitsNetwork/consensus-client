package units.network.test.docker

import org.testcontainers.containers.wait.strategy.DockerHealthcheckWaitStrategy

import java.nio.file.Path

abstract class BaseContainer(val hostName: String) {
  protected val container: GenericContainer

  def start(): Unit = {
    container.start()
  }

  def waitReady(): Unit = {
    container.waitingFor(new DockerHealthcheckWaitStrategy)
  }

  def stop(): Unit = {
    container.stop()
  }
}

object BaseContainer {
  val ConfigsDir: Path     = Path.of(System.getenv("CONFIGS_DIR"))
  val DefaultLogsDir: Path = Path.of(System.getenv("LOGS_DIR"))
}
