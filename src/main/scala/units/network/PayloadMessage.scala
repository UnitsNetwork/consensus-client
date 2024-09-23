package units.network

import cats.syntax.either.*
import com.google.common.primitives.Bytes
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto.SignatureLength
import play.api.libs.json.{JsObject, Json}
import units.client.engine.model.{ExecutionPayload, Withdrawal}
import units.eth.EthAddress
import units.{BlockHash, ExecutionPayloadInfo}
import units.util.HexBytesConverter.*

import scala.util.Try

class PayloadMessage private (
    payloadJson: JsObject,
    val hash: BlockHash,
    val signature: Option[ByteStr]
) {
  lazy val payload: Either[String, ExecutionPayloadInfo] = {
    (for {
      timestamp     <- (payloadJson \ "timestamp").asOpt[String].map(toLong).toRight("timestamp not defined")
      height        <- (payloadJson \ "blockNumber").asOpt[String].map(toLong).toRight("height not defined")
      parentHash    <- (payloadJson \ "parentHash").asOpt[BlockHash].toRight("parent hash not defined")
      stateRoot     <- (payloadJson \ "stateRoot").asOpt[String].toRight("state root not defined")
      feeRecipient  <- (payloadJson \ "feeRecipient").asOpt[EthAddress].toRight("fee recipient not defined")
      baseFeePerGas <- (payloadJson \ "baseFeePerGas").asOpt[String].map(toUint256).toRight("baseFeePerGas not defined")
      gasLimit      <- (payloadJson \ "gasLimit").asOpt[String].map(toLong).toRight("gasLimit not defined")
      gasUsed       <- (payloadJson \ "gasUsed").asOpt[String].map(toLong).toRight("gasUsed not defined")
      prevRandao    <- (payloadJson \ "prevRandao").asOpt[String].toRight("prevRandao not defined")
      withdrawals   <- (payloadJson \ "withdrawals").asOpt[Vector[Withdrawal]].toRight("withdrawals are not defined")
    } yield {
      ExecutionPayloadInfo(
        ExecutionPayload(
          hash,
          parentHash,
          stateRoot,
          height,
          timestamp,
          feeRecipient,
          baseFeePerGas,
          gasLimit,
          gasUsed,
          prevRandao,
          withdrawals
        ),
        payloadJson
      )
    }).leftMap(err => s"Error creating payload for block $hash: $err")
  }

  def toBytes: Array[Byte] = {
    val signatureBytes = signature.map(sig => Bytes.concat(Array(1.toByte), sig.arr)).getOrElse(Array(0.toByte))
    Bytes.concat(signatureBytes, Json.toBytes(payloadJson))
  }
}

object PayloadMessage {
  def apply(payloadJson: JsObject): Either[String, PayloadMessage] =
    apply(payloadJson, None)

  def apply(payloadJson: JsObject, hash: BlockHash, signature: Option[ByteStr]): PayloadMessage =
    new PayloadMessage(payloadJson, hash, signature)

  def apply(payloadJson: JsObject, signature: Option[ByteStr]): Either[String, PayloadMessage] =
    (payloadJson \ "blockHash")
      .asOpt[BlockHash]
      .toRight("Error creating payload: block hash not defined")
      .map(PayloadMessage(payloadJson, _, signature))

  def apply(payloadBytes: Array[Byte], signature: Option[ByteStr]): Either[String, PayloadMessage] = for {
    payload <- Try(Json.parse(payloadBytes).as[JsObject]).toEither.leftMap(err => s"Payload bytes are not a valid JSON object: ${err.getMessage}")
    block   <- apply(payload, signature)
  } yield block

  def fromBytes(bytes: Array[Byte]): Either[String, PayloadMessage] = {
    val isWithSignature = bytes.headOption.contains(1.toByte)
    val signature       = if (isWithSignature) Some(ByteStr(bytes.slice(1, SignatureLength + 1))) else None
    val payloadOffset   = if (isWithSignature) SignatureLength + 1 else 1

    for {
      _  <- validateSignatureLength(signature)
      pm <- apply(bytes.drop(payloadOffset), signature)
    } yield pm
  }

  private def validateSignatureLength(signature: Option[ByteStr]): Either[String, Unit] =
    Either.cond(signature.forall(_.size == SignatureLength), (), "Invalid block signature size")
}