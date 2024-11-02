package units.test

import org.scalatest.concurrent.Eventually.PatienceConfig

import scala.concurrent.duration.{Deadline, DurationInt}
import scala.util.{Failure, Success, Try}

trait HasRetry {
  protected implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = 30.seconds, interval = 1.second)

  protected def retryWithAttempts[ResponseT](f: Int => ResponseT)(implicit patienceConfig: PatienceConfig): ResponseT = {
    var attempt = 0
    retry {
      attempt += 1
      f(attempt)
    }
  }

  // Eventually has issues with handling patienceConfig
  protected def retry[ResponseT](f: => ResponseT)(implicit patienceConfig: PatienceConfig): ResponseT = {
    val deadline = Deadline.now + patienceConfig.timeout

    var r = Try(f)
    while (r.isFailure && deadline.hasTimeLeft()) {
      Thread.sleep(patienceConfig.interval.toMillis)
      r = Try(f)
    }

    r match {
      case Failure(e) => throw new RuntimeException(s"All attempts are out: $patienceConfig", e)
      case Success(r) => r
    }
  }

  protected def failRetry(message: String): Nothing = throw new RuntimeException(message)
}
