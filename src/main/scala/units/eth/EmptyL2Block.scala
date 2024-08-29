package units.eth

import units.BlockHash
import units.client.http.model.EcBlock
import units.util.HexBytesConverter
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.rlp.{RlpEncoder, RlpList, RlpString}
import play.api.libs.json.{JsObject, Json}

object EmptyL2Block {
  case class Params(
      parentHash: BlockHash,
      parentStateRoot: String,
      parentGasLimit: Long,
      newBlockTimestamp: Long,
      newBlockNumber: Long,
      baseFee: Uint256,
      feeRecipient: EthAddress = EthAddress.empty
  )

  private val InternalBlockTimestampDiff = 1 // seconds

  def mkExecutionPayload(parent: EcBlock, feeRecipient: EthAddress = EthAddress.empty): JsObject =
    mkExecutionPayload(
      Params(
        parentHash = parent.hash,
        parentStateRoot = parent.stateRoot,
        parentGasLimit = parent.gasLimit,
        newBlockTimestamp = parent.timestamp + InternalBlockTimestampDiff,
        newBlockNumber = parent.height + 1,
        baseFee = calculateGasFee(
          parentGasLimit = parent.gasLimit,
          parentBaseFeePerGas = parent.baseFeePerGas,
          parentGasUsed = parent.gasUsed
        ),
        feeRecipient = feeRecipient
      )
    )

  def mkExecutionPayload(params: Params): JsObject = Json.obj(
    "parentHash"    -> params.parentHash,
    "feeRecipient"  -> params.feeRecipient,
    "stateRoot"     -> params.parentStateRoot,
    "receiptsRoot"  -> EthereumConstants.EmptyRootHashHex,
    "logsBloom"     -> EthereumConstants.EmptyLogsBloomHex,
    "prevRandao"    -> EthereumConstants.EmptyPrevRandaoHex,
    "blockNumber"   -> HexBytesConverter.toHex(params.newBlockNumber),
    "gasLimit"      -> HexBytesConverter.toHex(params.parentGasLimit),
    "gasUsed"       -> EthereumConstants.ZeroHex,
    "timestamp"     -> HexBytesConverter.toHex(params.newBlockTimestamp),
    "extraData"     -> EthereumConstants.NullHex,
    "baseFeePerGas" -> HexBytesConverter.toHex(params.baseFee),
    "blockHash"     -> calculateHash(params),
    "transactions"  -> Json.arr(),
    "withdrawals"   -> Json.arr(),
    "blobGasUsed"   -> EthereumConstants.ZeroHex,
    "excessBlobGas" -> EthereumConstants.ZeroHex
  )

  def calculateHash(params: Params): BlockHash = {
    // Tip: if you want to enrich this, find an empty block with eth_getBlockByNumber
    // Also see BlockHeader.writeTo in besu
    val bytes = RlpEncoder.encode(
      new RlpList(
        rlpString(params.parentHash),
        EthereumConstants.OmmersHashRlp,
        rlpString(params.feeRecipient.hex), // coinbase == feeRecipient
        rlpString(params.parentStateRoot),
        EthereumConstants.EmptyRootHashRlp,  // transactionsRoot
        EthereumConstants.EmptyRootHashRlp,  // receiptsRoot
        EthereumConstants.EmptyLogsBloomRlp, // logsBloom
        EthereumConstants.NullRlp,           // Difficulty.ZERO.trimLeadingZeros() == Array[Byte](),
        RlpString.create(params.newBlockNumber),
        RlpString.create(params.parentGasLimit),
        EthereumConstants.NullRlp,                  // gasUsed
        RlpString.create(params.newBlockTimestamp), // timestamp
        EthereumConstants.NullRlp,                  // extraData
        EthereumConstants.EmptyPrevRandaoRlp,       // mixHashOrPrevRandao
        EthereumConstants.EmptyBlockNonceRlp,
        RlpString.create(params.baseFee.getValue), // baseFee
        EthereumConstants.EmptyRootHashRlp,        // withdrawalsRoot
        EthereumConstants.NullRlp,                 // blobGasUsed
        EthereumConstants.NullRlp,                 // excessBlobGas
        EthereumConstants.EmptyRootHashRlp         // parentBeaconBlockRoot
        // depositsRoot is null
      )
    )

    BlockHash(hash(bytes))
  }

  private val BaseFeeMaxChangeDenominator = 8
  private val ElasticityMultiplier        = 2

  // See an implemented algorithm org.hyperledger.besu.ethereum.mainnet.feemarket.{london, cancun, ...}
  // Current reference: LondonFeeMarket.computeBaseFee, because it didn't change in CancunFeeMarket.
  def calculateGasFee(parentGasLimit: Long, parentBaseFeePerGas: Uint256, parentGasUsed: Long): Uint256 = {
    val parentValue     = BigInt(parentBaseFeePerGas.getValue)
    val parentGasTarget = parentGasLimit / ElasticityMultiplier
    if (parentGasUsed == parentGasTarget) parentBaseFeePerGas
    else if (parentGasUsed > parentGasTarget) {
      val gasUsedDelta       = parentGasUsed - parentGasTarget
      val baseFeePerGasDelta = (parentValue * gasUsedDelta / parentGasTarget / BaseFeeMaxChangeDenominator).max(1)
      new Uint256((parentValue + baseFeePerGasDelta).bigInteger)
    } else {
      val gasUsedDelta       = parentGasTarget - parentGasUsed
      val baseFeePerGasDelta = (parentValue * gasUsedDelta / parentGasTarget / BaseFeeMaxChangeDenominator).toLong
      new Uint256((parentValue - baseFeePerGasDelta).bigInteger)
    }
  }
}
