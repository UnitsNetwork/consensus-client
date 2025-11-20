package units.eth

import com.wavesplatform.account.Address
import play.api.libs.json.*
import units.util.HexBytesConverter

opaque type EthAddress = String

object EthAddress {
  val AddressHexLength           = 40
  val AddressHexLengthWithPrefix = AddressHexLength + 2 // 0x

  val EmptyHex = "0x" + "0" * EthAddress.AddressHexLength
  val empty    = unsafeFrom(EmptyHex)

  extension (a: EthAddress) {
    def hex: String         = a
    def hexNoPrefix: String = a.drop(2)
  }

  given Format[EthAddress] = implicitly[Format[String]]

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

  def unapply(hex: String): Option[EthAddress] = from(hex).toOption

  def unsafeFrom(hex: String): EthAddress        = from(hex).left.map(new RuntimeException(_)).toTry.get
  def unsafeFrom(bytes: Array[Byte]): EthAddress = unsafeFrom(HexBytesConverter.toHex(bytes))
  def unsafeFrom(address: Address): EthAddress   = unsafeFrom(address.bytes.slice(2, 22))
}
