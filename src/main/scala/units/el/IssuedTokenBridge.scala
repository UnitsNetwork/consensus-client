package units.el

import com.wavesplatform.account.Address
import com.wavesplatform.transaction.utils.EthConverters.EthereumAddressExt
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Function
import units.eth.EthAddress
import units.util.HexBytesConverter

import java.math.BigInteger
import java.util
import java.util.Collections

object IssuedTokenBridge {
  val ReceiveIssuedFunction = "receiveIssued"
  val ReceiveIssuedGas      = BigInteger.valueOf(100_000L) // Should be enough to run this function

  // See https://specs.optimism.io/protocol/deposits.html#execution
  def mkDepositTransaction(transferNumber: Long, elContractAddress: EthAddress, sender: Address, recipient: EthAddress, amountInWaves: Long): String =
    DepositTransaction.create(
      sourceHash = DepositTransaction.mkUserDepositedSourceHash(transferNumber),
      from = sender.toEthAddress,
      to = elContractAddress.hex,
      mint = BigInteger.ZERO,
      value = BigInteger.ZERO,
      gas = ReceiveIssuedGas,
      isSystemTx = true, // Gas won't be consumed
      data = HexBytesConverter.toBytes(receiveIssuedCall(recipient, amountInWaves))
    )

  def receiveIssuedCall(receiver: EthAddress, amount: Long): String = {
    val function = new Function(
      ReceiveIssuedFunction,
      util.Arrays.asList[org.web3j.abi.datatypes.Type[?]](
        new org.web3j.abi.datatypes.Address(160, receiver.hexNoPrefix),
        new org.web3j.abi.datatypes.generated.Int64(amount)
      ),
      Collections.emptyList
    )
    FunctionEncoder.encode(function)
  }
}
