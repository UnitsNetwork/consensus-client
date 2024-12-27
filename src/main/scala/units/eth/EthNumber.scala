package units.eth

import play.api.libs.json.*
import supertagged.TaggedType
import units.util.HexBytesConverter

object EthNumber extends TaggedType[Long] {
  def apply(hex: String): EthNumber = EthNumber @@ HexBytesConverter.toLong(hex)
  def apply(n: Long): EthNumber     = EthNumber @@ n

  implicit lazy val jsonFormat: Format[EthNumber] = Format(
    Reads {
      case JsString(hex)                => JsSuccess(apply(hex))
      case JsNumber(n) if n.isValidLong => JsSuccess(apply(n.toLongExact))
      case x                            => JsError(s"Can't parse $x as number")
    },
    Writes.StringWrites.contramap(x => HexBytesConverter.toHex(x))
  )
}
