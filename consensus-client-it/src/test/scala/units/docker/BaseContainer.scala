package units.docker

import com.wavesplatform.utils.LoggerFacade
import org.slf4j.LoggerFactory
import org.testcontainers.containers.wait.strategy.DockerHealthcheckWaitStrategy

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
    container.getDockerClient.stopContainerCmd(container.getContainerId).exec()
    container.stop()
  }

  def logPorts(): Unit
}
