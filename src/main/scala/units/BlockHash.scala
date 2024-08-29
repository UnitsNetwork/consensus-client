package units

import com.wavesplatform.common.state.ByteStr
import units.util.HexBytesConverter
import play.api.libs.json.{Format, Reads, Writes}
import supertagged.TaggedType

object BlockHash extends TaggedType[String] {
  def apply(hex: String): BlockHash = {
    require(hex.startsWith("0x"), "Expected hash to start with 0x")
    require(hex.length == 66, s"Expected hash size of 66, got: ${hex.length}. Hex: $hex") // "0x" + 32 bytes
    BlockHash @@ hex
  }

  def apply(xs: ByteStr): BlockHash = BlockHash @@ HexBytesConverter.toHex(xs)

  def apply(xs: Array[Byte]): BlockHash = {
    require(xs.length == 32, "Block hash size must be 32 bytes")
    BlockHash @@ HexBytesConverter.toHex(xs)
  }

  implicit lazy val jsonFormat: Format[BlockHash] = Format(
    Reads.StringReads.map(apply),
    Writes.StringWrites.contramap(x => x)
  )
}
