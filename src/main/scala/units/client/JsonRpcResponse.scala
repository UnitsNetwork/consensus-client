package units.client

import play.api.libs.json.{Json, Reads}

case class JsonRpcResponse[A](result: Option[A], error: Option[ErrorResponse])
object JsonRpcResponse {
  implicit def reads[A: Reads]: Reads[JsonRpcResponse[A]] = Json.reads
}
