package units.eth

import units.util.HexBytesConverter
import play.api.libs.json.*

class EthAddress private (val hex: String) {
  def hexNoPrefix: String = hex.drop(2)

  override def hashCode(): Int = hex.hashCode()
  override def equals(that: Any): Boolean = that match {
    case that: EthAddress => this.hex == that.hex
    case _                => false
  }
  override def toString: String = hex
}

object EthAddress {
  val AddressHexLength           = 40
  val AddressHexLengthWithPrefix = AddressHexLength + 2 // 0x

  val EmptyHex = "0x" + "0" * EthAddress.AddressHexLength
  val empty    = unsafeFrom(EmptyHex)

  implicit val ethAddressFormat: Format[EthAddress] = implicitly[Format[String]].bimap(unsafeFrom, _.hex)

  def unsafeFrom(hex: String): EthAddress = {
    require(hex.startsWith("0x"), "Expected an address to start with 0x")
    require(hex.length == AddressHexLengthWithPrefix, s"Expected a hex string of $AddressHexLengthWithPrefix symbols, got: ${hex.length}. Hex: $hex")
    new EthAddress(hex.toLowerCase())
  }

  def unsafeFrom(bytes: Array[Byte]): EthAddress = unsafeFrom(HexBytesConverter.toHex(bytes))
}
