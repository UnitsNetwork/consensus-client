package units.client.engine.model

import play.api.libs.functional.syntax.*
import play.api.libs.json.*
import units.client.engine.model.Withdrawal.WithdrawalIndex
import units.eth.{EthAddress, Gwei}
import units.util.HexBytesConverter.*

case class Withdrawal(index: WithdrawalIndex, address: EthAddress, amount: Gwei)

object Withdrawal {
  type WithdrawalIndex = Long

  implicit val reads: Reads[Withdrawal] = (
    (JsPath \ "index").read[String].map(toLong) and
      (JsPath \ "address").read[EthAddress] and
      (JsPath \ "amount").read[Gwei]
    )(Withdrawal.apply)

  implicit val writes: Writes[Withdrawal] = (
    (JsPath \ "index").write[String].contramap[WithdrawalIndex](toHex) and
      (JsPath \ "validatorIndex").write[String] and // Ignore this field
      (JsPath \ "address").write[EthAddress] and
      (JsPath \ "amount").write[Gwei]
  ) { x => (x.index, "0x0", x.address, x.amount) }
}
