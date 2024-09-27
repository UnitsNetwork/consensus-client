package units.network

import cats.syntax.either.*
import com.google.common.primitives.Bytes
import com.wavesplatform.account.{PrivateKey, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
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
    val feeRecipient: EthAddress,
    val signature: Option[ByteStr]
) {
  lazy val jsonBytes: Array[Byte] = Json.toBytes(payloadJson)

  lazy val payloadInfo: Either[String, ExecutionPayloadInfo] = {
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
    }).leftMap(err => s"Error creating payload info for block $hash: $err")
  }

  def toBytes: Array[Byte] = {
    val signatureBytes = signature.map(sig => Bytes.concat(Array(1.toByte), sig.arr)).getOrElse(Array(0.toByte))
    Bytes.concat(signatureBytes, jsonBytes)
  }

  def isSignatureValid(pk: PublicKey): Boolean =
    signature.exists(crypto.verify(_, jsonBytes, pk, checkWeakPk = true))
}

object PayloadMessage {
  def apply(payloadJson: JsObject): Either[String, PayloadMessage] =
    apply(payloadJson, None)

  def apply(payloadJson: JsObject, hash: BlockHash, feeRecipient: EthAddress, signature: Option[ByteStr]): PayloadMessage =
    new PayloadMessage(payloadJson, hash, feeRecipient, signature)

  def apply(payloadJson: JsObject, signature: Option[ByteStr]): Either[String, PayloadMessage] =
    (for {
      hash         <- (payloadJson \ "blockHash").asOpt[BlockHash].toRight("block hash not defined")
      feeRecipient <- (payloadJson \ "feeRecipient").asOpt[EthAddress].toRight("fee recipient not defined")
    } yield PayloadMessage(payloadJson, hash, feeRecipient, signature))
      .leftMap(err => s"Error creating payload message: $err")

  def apply(payloadBytes: Array[Byte], signature: Option[ByteStr]): Either[String, PayloadMessage] = for {
    payload <- Try(Json.parse(payloadBytes).as[JsObject]).toEither.leftMap(err => s"Payload bytes are not a valid JSON object: ${err.getMessage}")
    block   <- apply(payload, signature)
  } yield block

  def signed(payloadJson: JsObject, signer: PrivateKey): Either[String, PayloadMessage] = {
    val signature = crypto.sign(signer, Json.toBytes(payloadJson))
    PayloadMessage(payloadJson, Some(signature))
  }

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
