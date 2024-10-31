package units

import com.wavesplatform.settings.Constants
import org.web3j.utils.Convert
import units.eth.Gwei

import java.math.BigInteger

object UnitsConvert {
  def toWavesAmount(userAmount: BigDecimal): Long = (userAmount * Constants.UnitsInWave).toLongExact
  def toWei(userAmount: BigDecimal): BigInteger   = Convert.toWei(userAmount.bigDecimal, Convert.Unit.ETHER).toBigIntegerExact
  def toGwei(userAmount: BigDecimal): Gwei = {
    val rawWei  = Convert.toWei(userAmount.bigDecimal, Convert.Unit.ETHER)
    val rawGwei = Convert.fromWei(rawWei, Convert.Unit.GWEI).longValue()
    Gwei.ofRawGwei(rawGwei)
  }
}
