package units

import com.wavesplatform.common.state.ByteStr
import play.api.libs.json.{Format, Reads, Writes}
import units.util.HexBytesConverter

opaque type BlockHash = String

object BlockHash {
  def apply(hex: String): BlockHash = {
    require(hex.startsWith("0x"), "Expected hash to start with 0x")
    require(hex.length == 66, s"Expected hash size of 66, got: ${hex.length}. Hex: $hex") // "0x" + 32 bytes
    hex
  }

  def apply(xs: ByteStr): BlockHash = apply(xs.arr)

  def apply(xs: Array[Byte]): BlockHash = {
    require(xs.length == 32, "Block hash size must be 32 bytes")
    HexBytesConverter.toHex(xs)
  }

  extension (bh: BlockHash) def str: String = bh

  given Format[BlockHash] = Format(
    Reads.StringReads.map(apply),
    Writes.StringWrites.contramap(x => x)
  )
}
