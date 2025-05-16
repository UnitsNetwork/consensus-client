package units

import com.wavesplatform.test.FlatSpec
import java.math.BigInteger

class WAmountTestSuite extends FlatSpec {
  "scale" should "Produce expected value" in {
    // e.g. 1.23456789 UNIT0 in CL should be 1.234567890000000000 in EL
    // the scale mathod takes (EL decimals - CL decimals)
    val decimalsDifference = 18 - 8
    WAmount(123456789L).scale(decimalsDifference) shouldBe EAmount(BigInteger("1234567890000000000"))
  }
}
