package units

import cats.syntax.either.*
import com.wavesplatform.account.PrivateKey
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
import com.wavesplatform.crypto.SignatureLength
import org.web3j.abi.datatypes.generated.Uint256
import play.api.libs.json.{JsObject, Json}
import units.client.CommonBlockData
import units.client.engine.model.{ExecutionPayload, Withdrawal}
import units.eth.EthAddress
import units.util.HexBytesConverter.*

// TODO Refactor to eliminate a manual deserialization, e.g. (raw: JsonObject, parsed: ParsedBlockL2)
class NetworkBlock private (
                             val hash: BlockHash,
                             val timestamp: Long, // UNIX epoch seconds
                             val height: Long,
                             val parentHash: BlockHash,
                             val stateRoot: String,
                             val feeRecipient: EthAddress,
                             val baseFeePerGas: Uint256,
                             val gasLimit: Long,
                             val gasUsed: Long,
                             val prevRandao: String,
                             val withdrawals: Vector[Withdrawal],
                             val payloadBytes: Array[Byte],
                             val payloadJson: JsObject,
                             val signature: Option[ByteStr]
) extends CommonBlockData {
  def isEpochFirstBlock: Boolean = withdrawals.nonEmpty

  def toPayload: ExecutionPayload = ExecutionPayload(
    hash = hash,
    parentHash = parentHash,
    stateRoot = stateRoot,
    height = height,
    timestamp = timestamp,
    feeRecipient = feeRecipient,
    baseFeePerGas = baseFeePerGas,
    gasLimit = gasLimit,
    gasUsed = gasUsed,
    prevRandao = prevRandao,
    withdrawals = withdrawals
  )

  override def toString: String = s"NetworkBlock($hash)"
}

object NetworkBlock {
  private def apply(payload: JsObject, payloadBytes: Array[Byte], signature: Option[ByteStr]): Either[ClientError, NetworkBlock] = {
    // See BlockToPayloadMapper for all available fields
    (for {
      hash               <- (payload \ "blockHash").asOpt[BlockHash].toRight("hash not defined")
      timestamp          <- (payload \ "timestamp").asOpt[String].map(toLong).toRight("timestamp not defined")
      height             <- (payload \ "blockNumber").asOpt[String].map(toLong).toRight("height not defined")
      parentHash         <- (payload \ "parentHash").asOpt[BlockHash].toRight("parent hash not defined")
      stateRoot          <- (payload \ "stateRoot").asOpt[String].toRight("state root not defined")
      minerRewardAddress <- (payload \ "feeRecipient").asOpt[EthAddress].toRight("fee recipient not defined")
      baseFeePerGas      <- (payload \ "baseFeePerGas").asOpt[String].map(toUint256).toRight("baseFeePerGas not defined")
      gasLimit           <- (payload \ "gasLimit").asOpt[String].map(toLong).toRight("gasLimit not defined")
      gasUsed            <- (payload \ "gasUsed").asOpt[String].map(toLong).toRight("gasUsed not defined")
      prevRandao         <- (payload \ "prevRandao").asOpt[String].toRight("prevRandao not defined")
      withdrawals        <- (payload \ "withdrawals").asOpt[Vector[Withdrawal]].toRight("withdrawals are not defined")
      _                  <- Either.cond(signature.forall(_.size == SignatureLength), (), "invalid signature size")
    } yield new NetworkBlock(
      hash,
      timestamp,
      height,
      parentHash,
      stateRoot,
      minerRewardAddress,
      baseFeePerGas,
      gasLimit,
      gasUsed,
      prevRandao,
      withdrawals,
      payloadBytes,
      payload,
      signature
    )).leftMap(err => ClientError(s"Error creating NetworkBlock from payload ${new String(payloadBytes)}: $err at payload"))
  }

  def apply(payloadBytes: Array[Byte], signature: Option[ByteStr]): Either[ClientError, NetworkBlock] = for {
    payload <- Json.parse(payloadBytes).asOpt[JsObject].toRight(ClientError("Payload is not a valid JSON object"))
    block   <- apply(payload, payloadBytes, signature)
  } yield block

  def signed(payload: JsObject, signer: PrivateKey): Either[ClientError, NetworkBlock] = {
    val payloadBytes = Json.toBytes(payload)
    NetworkBlock(payload, payloadBytes, Some(crypto.sign(signer, payloadBytes)))
  }

  def apply(payload: JsObject): Either[ClientError, NetworkBlock] = apply(payload, Json.toBytes(payload), None)
}
