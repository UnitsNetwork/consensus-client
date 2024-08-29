package units.eth

import org.web3j.utils.Numeric
import play.api.libs.json.Format

import java.math.BigInteger

class Gwei private (val amount: BigInteger) {
  def toHex: String = Numeric.toHexStringWithPrefix(amount)

  override def hashCode(): Int = amount.hashCode()
  override def equals(that: Any): Boolean = that match {
    case that: Gwei => this.amount.compareTo(that.amount) == 0
    case _          => false
  }
  override def toString: String = s"${amount.toString} Gwei"
}

object Gwei {
  implicit val gweiFormat: Format[Gwei] = implicitly[Format[String]].bimap(ofRawGwei, _.toHex)

  def ofRawGwei(x: Long): Gwei       = ofRawGwei(BigInteger.valueOf(x))
  def ofRawGwei(x: BigInteger): Gwei = new Gwei(x)
  def ofRawGwei(hex: String): Gwei   = Gwei.ofRawGwei(Numeric.toBigInt(hex))
}
