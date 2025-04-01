package units.el

import com.google.common.primitives.Longs
import com.wavesplatform.account.Address
import com.wavesplatform.crypto.Keccak256
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.rlp.{RlpEncoder, RlpList, RlpString}
import org.web3j.utils.Numeric
import play.api.libs.json.{JsValue, Json, Writes}
import units.eth.EthAddress
import units.util.HexBytesConverter

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
    to: EthAddress,
    mint: BigInteger,
    value: BigInteger,
    gas: BigInteger,
    isSystemTx: Boolean,
    data: String
) {
  def toHex: String = {
    val transactionType = 0x7e.toByte

    val rlpList = new RlpList(
      RlpString.create(sourceHash),
      RlpString.create(Numeric.hexStringToByteArray(from.hex)),
      RlpString.create(if (to == null) Array.emptyByteArray else Numeric.hexStringToByteArray(to.hex)),
      RlpString.create(mint),
      RlpString.create(value),
      RlpString.create(gas),
      RlpString.create(if (isSystemTx) 1 else 0),
      RlpString.create(HexBytesConverter.toBytes(data))
    )

    val rlpEncoded       = RlpEncoder.encode(rlpList)
    val transactionBytes = Array(transactionType) ++ rlpEncoded
    Numeric.toHexString(transactionBytes)
  }
}

object DepositedTransaction {
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
      "sourceHash"  -> HexBytesConverter.toHex(o.sourceHash),
      "gas"         -> Numeric.toHexStringWithPrefix(o.gas),
      "input"       -> Numeric.prependHexPrefix(o.data)
    )

}

enum DT {
  case FinalizeBridgeERC20(token: EthAddress, from: Address, to: EthAddress, amount: Uint256)
  case UpdateAssetRegistry(addedTokens: Seq[(EthAddress, Byte)])
}
