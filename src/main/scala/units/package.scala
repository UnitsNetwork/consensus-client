import monix.execution.{Cancelable, Scheduler}

import java.math.{BigDecimal, BigInteger}
import scala.concurrent.duration.FiniteDuration

package object units {
  val NativeTokenElDecimals: Byte = 18.toByte
  val NativeTokenClDecimals: Byte = 8.toByte

  type BlockHash    = BlockHash.Type
  type JobResult[A] = Either[ClientError, A]

  opaque type EAmount = BigInteger
  object EAmount:
    def apply(x: BigInteger): EAmount = x

  extension (x: EAmount) def raw: BigInteger = x

  opaque type WAmount = Long
  object WAmount:
    def apply(x: String): WAmount = x.toLong
    def apply(x: Long): WAmount   = x

  extension (x: WAmount) def scale(powerOfTen: Int): EAmount = BigDecimal.valueOf(x).scaleByPowerOfTen(powerOfTen).toBigIntegerExact

  extension (x: Scheduler)
    def scheduleOnceLabeled(label: String, initialDelay: FiniteDuration)(action: => Unit): Cancelable =
      x.scheduleOnce(
        initialDelay.length,
        initialDelay.unit,
        new Runnable {
          override def run(): Unit      = action
          override def toString: String = label
        }
      )
}
