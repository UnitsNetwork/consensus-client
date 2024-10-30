package units.docker

import com.wavesplatform.utils.LoggerFacade
import org.slf4j.LoggerFactory
import org.testcontainers.containers.wait.strategy.DockerHealthcheckWaitStrategy

import java.nio.file.Path

abstract class BaseContainer(val hostName: String) {
  protected lazy val log = LoggerFacade(LoggerFactory.getLogger(s"${getClass.getSimpleName}.$hostName"))

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

  def logPorts(): Unit
}

object BaseContainer {
  val ConfigsDir: Path         = Path.of(System.getProperty("cc.it.configs.dir"))
  val DefaultLogsDir: Path     = Path.of(System.getProperty("cc.it.logs.dir"))
  val WavesDockerImage: String = System.getProperty("cc.it.docker.image")
}
