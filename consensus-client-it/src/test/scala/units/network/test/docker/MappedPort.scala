package units.network.test.docker

import com.wavesplatform.utils.ScorexLogging

class MappedPort(container: GenericContainer, portInsideContainer: Int) extends ScorexLogging {
  @volatile private var currentPort = 0

  def apply(): Int = {
    if (currentPort == 0) {
      currentPort = container.getMappedPort(portInsideContainer)
      log.info(s"${container.getContainerName}: $currentPort->$portInsideContainer")
    }
    currentPort
  }

  def clean(): Unit = {
    currentPort = 0
  }
}
