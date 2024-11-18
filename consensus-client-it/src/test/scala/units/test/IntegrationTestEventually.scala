package units.test

import org.scalatest.concurrent.Eventually
import org.scalatest.enablers.{ConstantRetrying, Retrying}
import units.docker.WavesNodeContainer.AverageBlockDelay

import scala.concurrent.duration.DurationInt

trait IntegrationTestEventually extends Eventually {
  implicit def retrying[T]: Retrying[T]                = ConstantRetrying.create[T]
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(timeout = AverageBlockDelay * 2, interval = 1.second)
}
