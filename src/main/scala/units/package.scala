import java.math.{BigInteger, BigDecimal}

package object units {
  type BlockHash    = BlockHash.Type
  type JobResult[A] = Either[ClientError, A]

  opaque type EAmount = BigInteger
  object EAmount:
    def apply(x: BigInteger): EAmount = x

  extension (x: EAmount)
    def raw: BigInteger = x

  opaque type WAmount = Long
  object WAmount:
    def apply(x: String): WAmount = x.toLong

  extension (x: WAmount)
    def scale(powerOfTen: Int): EAmount = BigDecimal.valueOf(x).scaleByPowerOfTen(powerOfTen).toBigIntegerExact
}
