package units.client.engine.model

import play.api.libs.json.Reads

sealed abstract class PayloadStatus(val value: String) {
  override def toString: String = value
}

object PayloadStatus {
  case object Valid                                 extends PayloadStatus("VALID")
  case object Syncing                               extends PayloadStatus("SYNCING")
  case class Unexpected(override val value: String) extends PayloadStatus(value)

  implicit val reads: Reads[PayloadStatus] = Reads.of[String].map {
    case "VALID"   => Valid
    case "SYNCING" => Syncing
    case other     => Unexpected(other)
  }
}
