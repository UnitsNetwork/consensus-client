package units.optimism

import com.google.common.primitives.Longs
import com.wavesplatform.crypto.Keccak256
import org.web3j.rlp.{RlpEncoder, RlpList, RlpString}
import org.web3j.utils.Numeric

import java.math.BigInteger

object DepositTransactionBuilder {
  // https://eips.ethereum.org/EIPS/eip-2718
  // https://specs.optimism.io/protocol/deposits.html#the-deposited-transaction-type
  def mkDepositTransaction(
      sourceHash: Array[Byte],
      from: String,
      to: String,
      mint: BigInteger,
      value: BigInteger,
      gas: BigInteger,
      isSystemTx: Boolean,
      data: Array[Byte]
  ): String = {
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
    Numeric.toHexString(transactionBytes)
  }

  // https: //specs.optimism.io/protocol/deposits.html#source-hash-computation
  // TODO: blockNumber is not a part of spec
  def mkUserDepositedSourceHash(l1BlockHash: Array[Byte], l1LogIndex: Long, blockNumber: Long): Array[Byte] = {
    Keccak256.hash(Longs.toByteArray(blockNumber)) // YOLO
  }
}
