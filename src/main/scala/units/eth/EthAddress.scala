package units.eth

import play.api.libs.json.*
import units.util.HexBytesConverter

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

  def from(hex: String): Either[String, EthAddress] = {
    for {
      _ <- Either.cond(hex.startsWith("0x"), (), "expected an address to start with 0x")
      _ <- Either.cond(
        hex.length == AddressHexLengthWithPrefix,
        (),
        s"Expected a hex string of $AddressHexLengthWithPrefix symbols, got: ${hex.length}. Hex: $hex"
      )
    } yield new EthAddress(hex.toLowerCase())
  }.left.map(e => s"Can't decode address '$hex': $e")

  def unsafeFrom(hex: String): EthAddress        = from(hex).left.map(new RuntimeException(_)).toTry.get
  def unsafeFrom(bytes: Array[Byte]): EthAddress = unsafeFrom(HexBytesConverter.toHex(bytes))
}
