package units

import org.web3j.utils.Convert
import units.eth.Gwei

object UnitsConvert {
  def toUnitsInWaves(userAmount: BigDecimal): Long               = toWavesAtomic(userAmount, TestDefaults.NativeTokenClDecimals)
  def toWavesAtomic(userAmount: BigDecimal, decimals: Int): Long = toAtomic(userAmount, decimals).bigInteger.longValueExact()

  def toWei(userAmount: BigDecimal): BigInt = Convert.toWei(userAmount.bigDecimal, Convert.Unit.ETHER).toBigIntegerExact
  def toGwei(userAmount: BigDecimal): Gwei = {
    val rawWei  = Convert.toWei(userAmount.bigDecimal, Convert.Unit.ETHER)
    val rawGwei = Convert.fromWei(rawWei, Convert.Unit.GWEI).longValue()
    Gwei.ofRawGwei(rawGwei)
  }

  def toAtomic(userAmount: BigDecimal, decimals: Int): BigInt = userAmount.bigDecimal.scaleByPowerOfTen(decimals).toBigIntegerExact
  def toUser(atomic: BigInt, decimals: Int): BigDecimal       = BigDecimal(atomic).bigDecimal.scaleByPowerOfTen(-decimals)
}
