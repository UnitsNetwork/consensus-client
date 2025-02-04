package units.util

import units.eth.EthereumConstants
import play.api.libs.json.{JsObject, JsString}

object BlockToPayloadMapper {
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

  def toPayloadJson(blockJson: JsObject, payloadBodyJson: JsObject): JsObject = {
    val blockJsonData = blockJson.value

    JsObject(
      fieldsMapping.flatMap { case (blockField, payloadField) =>
        blockJsonData.get(blockField).map(payloadField -> _)
      } ++ List(
        "blobGasUsed"   -> JsString(EthereumConstants.ZeroHex),
        "excessBlobGas" -> JsString(EthereumConstants.ZeroHex)
      )
    ) ++ payloadBodyJson
  }

}
