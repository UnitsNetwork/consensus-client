package units

import com.wavesplatform.settings.Constants
import org.web3j.utils.Convert

import java.math.BigInteger

object UnitsConvert {
  def toWavesAmount(userAmount: BigDecimal): Long     = (userAmount * Constants.UnitsInWave).toLongExact
  def toEthAmount(userAmount: BigDecimal): BigInteger = Convert.toWei(userAmount.bigDecimal, Convert.Unit.ETHER).toBigIntegerExact
}
