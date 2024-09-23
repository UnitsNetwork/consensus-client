package units

import com.wavesplatform.common.state.ByteStr
import units.util.HexBytesConverter
import play.api.libs.json.{Format, Reads, Writes}
import supertagged.TaggedType

object BlockHash extends TaggedType[String] {

  val BytesSize: Int = 32
  val HexSize: Int   = 66

  def apply(hex: String): BlockHash = {
    require(hex.startsWith("0x"), "Expected hash to start with 0x")
    require(hex.length == HexSize, s"Expected hash size of $HexSize, got: ${hex.length}. Hex: $hex") // "0x" + 32 bytes
    BlockHash @@ hex
  }

  def apply(xs: ByteStr): BlockHash = BlockHash @@ HexBytesConverter.toHex(xs)

  def apply(xs: Array[Byte]): BlockHash = {
    require(xs.length == BytesSize, s"Block hash size must be $BytesSize bytes")
    BlockHash @@ HexBytesConverter.toHex(xs)
  }

  implicit lazy val jsonFormat: Format[BlockHash] = Format(
    Reads.StringReads.map(apply),
    Writes.StringWrites.contramap(x => x)
  )
}
