package units.client.engine.model

import units.util.HexBytesConverter

enum BlockNumber(val str: String) {
  case Latest         extends BlockNumber("latest")
  case Number(n: Int) extends BlockNumber(HexBytesConverter.toHex(n))
}
