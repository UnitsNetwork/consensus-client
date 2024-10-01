package units

import cats.syntax.either.*
import com.wavesplatform.utils.ScorexLogging

import java.util.concurrent.ThreadLocalRandom
import scala.util.chaining.scalaUtilChainingOps

trait HasJobLogging extends ScorexLogging {
  protected def wrap[A](method: String, f: => Either[String, A], toMsg: A => String = (_: A).toString): Either[String, A] = {
    val currRequestId = ThreadLocalRandom.current().nextInt(10000, 100000).toString
    log.debug(s"[$currRequestId] $method")

    f.tap {
      case Left(e)  => log.debug(s"[$currRequestId] Error: $e")
      case Right(r) => log.debug(s"[$currRequestId] Result: ${toMsg(r)}")
    }
  }

  protected def l(text: String): Either[String, Unit] = log.debug(text).asRight
}
