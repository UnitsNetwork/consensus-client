package units.util

import com.wavesplatform.common.state.ByteStr
import units.BlockHash
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.utils.Numeric

import java.math.BigInteger

object HexBytesConverter {

  def toByteStr(hash: BlockHash): ByteStr =
    ByteStr(toBytes(hash.toString))

  def toInt(intHex: String): Int =
    Numeric.toBigInt(intHex).intValueExact()

  def toLong(longHex: String): Long =
    Numeric.toBigInt(longHex).longValueExact()

  def toUint256(longHex: String): Uint256 =
    new Uint256(Numeric.toBigInt(longHex))

  def toBytes(hex: String): Array[Byte] =
    Numeric.hexStringToByteArray(hex)

  def toHex(l: Long): String =
    Numeric.toHexStringWithPrefix(BigInteger.valueOf(l))

  def toHex(bs: ByteStr): String =
    Numeric.toHexString(bs.arr)

  def toHex(bytes: Array[Byte]): String =
    Numeric.toHexString(bytes)

  def toHexNoPrefix(bytes: Array[Byte]): String =
    Numeric.toHexStringNoPrefix(bytes)

  def toHex(x: Uint256): String =
    Numeric.toHexStringWithPrefix(x.getValue)
}
