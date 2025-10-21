package units.eth

import play.api.libs.json.*
import units.util.HexBytesConverter

opaque type EthNumber = Long

object EthNumber {
  def apply(hex: String): EthNumber = HexBytesConverter.toLong(hex)
  def apply(n: Long): EthNumber     = n

  given Format[EthNumber] = Format(
    Reads {
      case JsString(hex)                => JsSuccess(apply(hex))
      case JsNumber(n) if n.isValidLong => JsSuccess(apply(n.toLongExact))
      case x                            => JsError(s"Can't parse $x as number")
    },
    Writes.StringWrites.contramap(x => HexBytesConverter.toHex(x))
  )
}
