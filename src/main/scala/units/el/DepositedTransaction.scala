package units.el

import com.google.common.primitives.Longs
import com.wavesplatform.crypto.Keccak256
import org.web3j.rlp.{RlpEncoder, RlpList, RlpString}
import org.web3j.utils.Numeric
import supertagged.TaggedType

import java.math.BigInteger

object DepositedTransaction extends TaggedType[String] {

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
  def create(
      sourceHash: Array[Byte],
      from: String,
      to: String,
      mint: BigInteger,
      value: BigInteger,
      gas: BigInteger,
      isSystemTx: Boolean,
      data: Array[Byte]
  ): Type = {
    val transactionType = 0x7e.toByte

    val rlpList = new RlpList(
      RlpString.create(sourceHash),
      RlpString.create(Numeric.hexStringToByteArray(from)),
      RlpString.create(if (to == null) Array.emptyByteArray else Numeric.hexStringToByteArray(to)),
      RlpString.create(mint),
      RlpString.create(value),
      RlpString.create(gas),
      RlpString.create(if (isSystemTx) 1 else 0),
      RlpString.create(data)
    )

    val rlpEncoded       = RlpEncoder.encode(rlpList)
    val transactionBytes = Array(transactionType) ++ rlpEncoded
    DepositedTransaction(Numeric.toHexString(transactionBytes))
  }

  def mkUserDepositedSourceHash(transferNumber: Long): Array[Byte] =
    Keccak256.hash(Longs.toByteArray(transferNumber))
}
