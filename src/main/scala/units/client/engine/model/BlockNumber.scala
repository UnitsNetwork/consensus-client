package units.client.engine.model

import units.util.HexBytesConverter

sealed abstract class BlockNumber(val str: String)
object BlockNumber {
  case object Latest        extends BlockNumber("latest")
  case class Number(n: Int) extends BlockNumber(HexBytesConverter.toHex(n))
}
