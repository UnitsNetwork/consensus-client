package units.network.test.docker

import java.net.ServerSocket
import scala.util.Using

object Ports {
  case class AvailablePortsNotFound() extends Throwable

  private val portsRange = Option(System.getenv("TEST_PORT_RANGE")).fold(throw new RuntimeException("Specify TEST_PORT_RANGE"))(parsePortRange)

  private var currentPosition = portsRange.start

  def nextFreePort(): Int =
    findFreePortIn(currentPosition to portsRange.end).fold(throw AvailablePortsNotFound()) { newPort =>
      currentPosition = (newPort + 1).min(portsRange.end)
      newPort
    }

  private def findFreePortIn(range: Range): Option[Int] =
    range.find { i =>
      Using(new ServerSocket(i))(_.getLocalPort).isSuccess
    }

  private def parsePortRange(stringRange: String): Range.Inclusive = {
    val (first, second) = stringRange.split('-').map(_.toInt) match {
      case Array(first, second) => (first, second)
      case _                    => throw new IllegalArgumentException(s"Illegal port range for tests! $stringRange")
    }
    if (first >= second)
      throw new IllegalArgumentException(s"Illegal port range for tests! First boundary $first is bigger or equals second $second!")
    first to second
  }
}
