package units

import com.wavesplatform.crypto.Keccak256
import units.util.HexBytesConverter
import org.web3j.rlp.RlpString

package object eth {
  type EthNumber = EthNumber.Type

  def hash(xs: Array[Byte]): Array[Byte] = Keccak256.hash(xs)

  def rlpString(hex: String): RlpString    = rlpArray(HexBytesConverter.toBytes(hex))
  def rlpArray(xs: Array[Byte]): RlpString = RlpString.create(xs)
}
