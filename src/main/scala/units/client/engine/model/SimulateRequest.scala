package units.client.engine.model

import com.wavesplatform.common.state.ByteStr
import eu.timepit.refined.types.string.HexString
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.utils
import play.api.libs.json.{JsValue, Json, Writes}
import units.BlockHash
import units.el.DepositedTransaction
import units.el.DepositedTransaction.given
import units.eth.EthAddress
import units.util.HexBytesConverter

import java.math.BigInteger

case class BlockOverrides(
    number: Long,
    baseFeePerGas: Uint256,
    feeRecipient: EthAddress,
    time: Long,
    withdrawals: Seq[Withdrawal]
)
given Writes[BlockOverrides]:
  override def writes(o: BlockOverrides): JsValue = Json.obj(
    "number"        -> HexBytesConverter.toHex(o.number),
    "time"          -> HexBytesConverter.toHex(o.time),
    "baseFeePerGas" -> HexBytesConverter.toHex(o.baseFeePerGas),
    "feeRecipient"  -> o.feeRecipient,
    "withdrawals"   -> o.withdrawals
  )

case class BlockStateCall(blockOverrides: BlockOverrides, calls: Seq[DepositedTransaction])
given Writes[BlockStateCall] = Json.writes

case class SimulateRequest(blockStateCalls: Seq[BlockStateCall], hash: BlockHash, id: Int)
given Writes[SimulateRequest]:
  override def writes(o: SimulateRequest): JsValue = Json.obj(
    "jsonrpc" -> "2.0",
    "method"  -> "eth_simulateV1",
    "id"      -> o.id,
    "params" -> Json.arr(
      Json.obj(
        "blockStateCalls"        -> o.blockStateCalls,
        "validation"             -> true,
        "returnFullTransactions" -> false,
        "traceTransfers"         -> false
      ),
      o.hash
    )
  )
