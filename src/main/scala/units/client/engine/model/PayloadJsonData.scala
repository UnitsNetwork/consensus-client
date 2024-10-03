package units.client.engine.model

import play.api.libs.json.{JsObject, JsString}
import units.client.engine.model.PayloadJsonData.fieldsMapping
import units.eth.EthereumConstants

case class PayloadJsonData(blockJson: JsObject, bodyJson: JsObject) {
  def toPayloadJson: JsObject = {
    val blockJsonData = blockJson.value

    JsObject(
      fieldsMapping.flatMap { case (blockField, payloadField) =>
        blockJsonData.get(blockField).map(payloadField -> _)
      } ++ List(
        "blobGasUsed"   -> JsString(EthereumConstants.ZeroHex),
        "excessBlobGas" -> JsString(EthereumConstants.ZeroHex)
      )
    ) ++ bodyJson
  }
}

object PayloadJsonData {
  private val commonFields =
    Seq(
      "parentHash",
      "stateRoot",
      "receiptsRoot",
      "logsBloom",
      "gasLimit",
      "gasUsed",
      "timestamp",
      "extraData",
      "baseFeePerGas"
    )

  private val fieldsMapping =
    Seq("miner" -> "feeRecipient", "number" -> "blockNumber", "hash" -> "blockHash", "mixHash" -> "prevRandao") ++ commonFields.map(field =>
      field -> field
    )
}