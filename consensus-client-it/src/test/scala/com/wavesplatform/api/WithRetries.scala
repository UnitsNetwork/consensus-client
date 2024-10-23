package com.wavesplatform.api

import com.wavesplatform.api
import com.wavesplatform.utils.LoggerFacade
import org.slf4j.LoggerFactory

import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.{DurationInt, FiniteDuration}

case class WithRetries(maxAttempts: Int = 30, delay: FiniteDuration = 1.second, message: String = "") {
  def untilDefined[InputT](f: => Option[InputT]): InputT = until(f) { case Some(r) => r }

  def until[InputT, OutputT](f: => InputT)(cond: PartialFunction[InputT, OutputT]): OutputT = {
    val values = Iterator.single(f) ++ Iterator.continually {
      Thread.sleep(delay.toMillis)
      f
    }

    val idPrefix = s"[${ThreadLocalRandom.current().nextInt(0, 100_000)}]"
    WithRetries.log.trace(s"$idPrefix $message")
    values
      .take(maxAttempts)
      .tapEach { x =>
        WithRetries.log.trace(s"$idPrefix $x")
      }
      .collectFirst(cond) match {
      case Some(r) => r
      case None =>
        throw new RuntimeException(s"$idPrefix $message: $maxAttempts attempts are out!")
    }
  }
}

object WithRetries {
  protected lazy val log = LoggerFacade(LoggerFactory.getLogger(classOf[api.WithRetries].getSimpleName))
}
