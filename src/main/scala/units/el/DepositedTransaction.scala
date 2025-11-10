package units.el

import cats.implicits.{catsSyntaxEither, catsSyntaxOptionId}
import com.google.common.primitives.Longs
import com.wavesplatform.crypto.Keccak256
import org.web3j.rlp.{RlpEncoder, RlpList, RlpString}
import org.web3j.utils.Numeric
import play.api.libs.functional.syntax.*
import play.api.libs.json.*
import units.eth.EthAddress
import units.util.HexBytesConverter
import units.util.HexBytesConverter.*

import java.math.BigInteger

/** @param sourceHash
  *   Uniquely identifies the origin of the deposit
  * @param from
  *   The address of the sender account
  * @param to
  *   The address of the recipient account, or the null (zero-length) address if the deposited transaction is a contract creation
  * @param mint
  *   The ETH value to mint on EL
  * @param value
  *   The ETH value to send to the recipient account
  * @param gas
  *   The gas limit for the EL transaction
  * @param isSystemTx
  *   If true, the transaction does not interact with the L2 block gas pool
  * @param data
  *   The calldata
  * @return
  *   HEX-coded transaction bytes
  * @see
  *   https://specs.optimism.io/protocol/deposits.html#the-deposited-transaction-type
  * @see
  *   https://eips.ethereum.org/EIPS/eip-2718
  */
case class DepositedTransaction(
    sourceHash: Array[Byte],
    from: EthAddress,
    to: Option[EthAddress],
    mint: BigInteger,
    value: BigInteger,
    gas: BigInteger,
    isSystemTx: Boolean,
    data: String
) {
  def toHex: String = {

    val rlpList = new RlpList(
      RlpString.create(sourceHash),
      RlpString.create(Numeric.hexStringToByteArray(from.hex)),
      RlpString.create(to.fold(Array.emptyByteArray)(toAddress => Numeric.hexStringToByteArray(toAddress.hex))),
      RlpString.create(mint),
      RlpString.create(value),
      RlpString.create(gas),
      RlpString.create(if (isSystemTx) 1 else 0),
      RlpString.create(toBytes(data))
    )

    val rlpEncoded       = RlpEncoder.encode(rlpList)
    val transactionBytes = Array(DepositedTransaction.Type) ++ rlpEncoded
    Numeric.toHexString(transactionBytes)
  }

  override def equals(obj: Any): Boolean = obj match {
    case that: DepositedTransaction =>
      this.sourceHash.sameElements(that.sourceHash) &&
      this.from == that.from &&
      this.to == that.to &&
      this.mint == that.mint &&
      this.value == that.value &&
      this.gas == that.gas &&
      this.isSystemTx == that.isSystemTx &&
      this.data == that.data
    case _ => false
  }

  def hash: String = HexBytesConverter.toHex(Keccak256.hash(this.toBytes))
}

object DepositedTransaction {
  val Type: Byte = 0x7e.toByte
  def mkUserDepositedSourceHash(transferIndex: Long): Array[Byte] =
    mkUserDepositedSourceHash(Longs.toByteArray(transferIndex))

  def mkUserDepositedSourceHash(raw: Array[Byte]): Array[Byte] = Keccak256.hash(raw)

  given Writes[DepositedTransaction]:
    override def writes(o: DepositedTransaction): JsValue = Json.obj(
      "from"        -> o.from,
      "to"          -> o.to,
      "mint"        -> Numeric.toHexStringWithPrefix(o.mint),
      "value"       -> Numeric.toHexStringWithPrefix(o.value),
      "isDepositTx" -> true,
      "isSystemTx"  -> o.isSystemTx,
      "sourceHash"  -> toHex(o.sourceHash),
      "gas"         -> Numeric.toHexStringWithPrefix(o.gas),
      "input"       -> Numeric.prependHexPrefix(o.data)
    )

  def parseValidDepositedTransaction(json: JsValue): Either[String, Option[DepositedTransaction]] =
    if (toInt((json \ "type").as[String]) == Type)
      ((JsPath \ "sourceHash").read[String].map(toBytes) and
        (JsPath \ "from").read[EthAddress] and
        (JsPath \ "to").readNullable[EthAddress] and
        (JsPath \ "mint").read[String].map(toUint256).map(_.getValue) and
        (JsPath \ "value").read[String].map(toUint256).map(_.getValue) and
        (JsPath \ "gas").read[String].map(toUint256).map(_.getValue) and
        (JsPath \ "isSystemTx").read[Boolean] and
        (JsPath \ "input").read[String])(DepositedTransaction.apply)
        .reads(json)
        .asEither
        .bimap(e => s"Error parsing Deposited Transaction: ${Json.stringify(JsError.toJson(e))}", _.some)
    else Right(None)
}
