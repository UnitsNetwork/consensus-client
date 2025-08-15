package units.el

import com.wavesplatform.test.FlatSpec
import units.eth.EthAddress

import java.math.BigInteger

class DepositedTransactionTestSuite extends FlatSpec {
  "equals" should "return true for transaction made of equal values" in {
    val dt1 = DepositedTransaction(
      sourceHash = DepositedTransaction.mkUserDepositedSourceHash(42L),
      from = EthAddress.unsafeFrom("0x0000000000000000000000000000000000000000"),
      to = Some(EthAddress.unsafeFrom("0xa50a51c09a5c451c52bb714527e1974b686d8e77")),
      mint = BigInteger.valueOf(0),
      value = BigInteger.valueOf(0),
      gas = BigInteger.valueOf(1000000),
      isSystemTx = true,
      data =
        "0xdc00abd10000000000000000000000009a3dbca554e9f6b9257aaa24010da8377c57c17e000000000000000000000000dc1c695f29d77b4ea04281c4833730f5efd0209f000000000000000000000000aaaa00000000000000000000000000000000aaaa0000000000000000000000000000000000000000000000000000000005f5e100"
    )
    val dt2 = DepositedTransaction(
      sourceHash = DepositedTransaction.mkUserDepositedSourceHash(42L),
      from = EthAddress.unsafeFrom("0x0000000000000000000000000000000000000000"),
      to = Some(EthAddress.unsafeFrom("0xa50a51c09a5c451c52bb714527e1974b686d8e77")),
      mint = BigInteger.valueOf(0),
      value = BigInteger.valueOf(0),
      gas = BigInteger.valueOf(1000000),
      isSystemTx = true,
      data =
        "0xdc00abd10000000000000000000000009a3dbca554e9f6b9257aaa24010da8377c57c17e000000000000000000000000dc1c695f29d77b4ea04281c4833730f5efd0209f000000000000000000000000aaaa00000000000000000000000000000000aaaa0000000000000000000000000000000000000000000000000000000005f5e100"
    )
    dt1 shouldBe dt2
  }
}
