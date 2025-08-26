package units

import com.wavesplatform.test.FlatSpec

import java.math.BigInteger

class WAmountTestSuite extends FlatSpec {
  val unit0decimalsDifference = NativeTokenElDecimals - NativeTokenClDecimals

  "scale" should "1 UNIT0: scale CL amount to EL amount given the decimals difference" in {
    val userAmount           = 1
    val nativeTokensClAmount = UnitsConvert.toUnitsInWaves(userAmount)
    val nativeTokensElAmount = WAmount(nativeTokensClAmount).scale(unit0decimalsDifference)

    // 1 UNIT0 in CL is represented as 100_000_000L
    nativeTokensClAmount shouldBe 100_000_000L

    // 1 UNIT0 in EL is represented as 1_000_000_000_000_000_000
    nativeTokensElAmount.raw shouldBe BigInt("1000000000000000000").bigInteger
  }

  "scale" should "1.23456789 UNIT0: scale CL amount to EL amount given the decimals difference" in {
    // 1.23456789 UNIT0 in CL is represented as 123456789L
    val clAmount = WAmount(123456789L)

    // 1.234567890000000000 UNIT0 in EL is represented as 1234567890000000000
    val ecpecteedElAmount = EAmount(BigInteger("1234567890000000000"))
    clAmount.scale(unit0decimalsDifference) shouldBe ecpecteedElAmount
  }
}
