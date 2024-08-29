package units.client

import play.api.libs.json.{Json, Reads}

case class ErrorResponse(message: String)
object ErrorResponse {
  implicit val reads: Reads[ErrorResponse] = Json.reads
}
