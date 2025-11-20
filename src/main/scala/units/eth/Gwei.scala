package units.eth

import org.web3j.utils.Numeric
import play.api.libs.json.Format

import java.math.BigInteger

opaque type Gwei = BigInteger

object Gwei {
  implicit val gweiFormat: Format[Gwei] = implicitly[Format[String]].bimap(ofRawGwei, Numeric.toHexStringWithPrefix)

  extension (g: Gwei) def amount: BigInteger = g

  def ofRawGwei(x: Long): Gwei       = ofRawGwei(BigInteger.valueOf(x))
  def ofRawGwei(x: BigInteger): Gwei = x
  def ofRawGwei(hex: String): Gwei   = Gwei.ofRawGwei(Numeric.toBigInt(hex))
}
