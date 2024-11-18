package org.scalatest.enablers

import org.scalactic.source
import org.scalatest.Resources
import org.scalatest.Suite.anExceptionThatShouldCauseAnAbort
import org.scalatest.exceptions.{StackDepthException, TestFailedDueToTimeoutException, TestPendingException}
import org.scalatest.time.{Nanosecond, Span}

import scala.annotation.tailrec

// Retrying with a constant intervals. Copy-paste from Retrying.retryingNatureOfT without excess logic
object ConstantRetrying {
  def create[T]: Retrying[T] = new Retrying[T] {
    override def retry(timeout: Span, interval: Span, pos: source.Position)(fun: => T): T = {
      val startNanos = System.nanoTime
      def makeAValiantAttempt(): Either[Throwable, T] = {
        try {
          Right(fun)
        } catch {
          case tpe: TestPendingException                             => throw tpe
          case e: Throwable if !anExceptionThatShouldCauseAnAbort(e) => Left(e)
        }
      }

      @tailrec
      def tryTryAgain(attempt: Int): T = {
        makeAValiantAttempt() match {
          case Right(result) => result
          case Left(e) =>
            val duration = System.nanoTime - startNanos
            if (duration < timeout.totalNanos) {
              Thread.sleep(interval.millisPart, interval.nanosPart)
            } else {
              val durationSpan = Span(1, Nanosecond).scaledBy(duration.toDouble) // Use scaledBy to get pretty units
              throw new TestFailedDueToTimeoutException(
                (_: StackDepthException) =>
                  Some(
                    if (e.getMessage == null)
                      Resources.didNotEventuallySucceed(attempt.toString, durationSpan.prettyString)
                    else
                      Resources.didNotEventuallySucceedBecause(attempt.toString, durationSpan.prettyString, e.getMessage)
                  ),
                Some(e),
                Left(pos),
                None,
                timeout
              )
            }

            tryTryAgain(attempt + 1)
        }
      }
      tryTryAgain(1)
    }
  }
}
