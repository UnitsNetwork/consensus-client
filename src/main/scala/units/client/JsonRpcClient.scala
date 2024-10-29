package units.client

import cats.Id
import cats.syntax.either.*
import play.api.libs.json.{JsError, JsValue, Reads, Writes}
import sttp.client3.*
import sttp.client3.playJson.*
import sttp.model.Uri
import units.client.JsonRpcClient.DefaultTimeout
import units.{ClientConfig, ClientError}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

trait JsonRpcClient {
  private type RpcRequest[B] = Request[Either[ResponseException[String, JsError], JsonRpcResponse[B]], Any]

  def config: ClientConfig
  def backend: SttpBackend[Id, ?]
  def apiUrl: Uri

  protected def sendRequest[RQ: Writes, RP: Reads](requestBody: RQ, timeout: FiniteDuration = DefaultTimeout): Either[String, Option[RP]] =
    sendRequest(mkRequest(requestBody, timeout), config.apiRequestRetries)

  protected def parseJson[A: Reads](jsValue: JsValue): Either[ClientError, A] =
    Try(jsValue.as[A]).toEither.leftMap(err => ClientError(s"Response parse error: ${err.getMessage}"))

  private def mkRequest[A: Writes, B: Reads](requestBody: A, timeout: FiniteDuration): RpcRequest[B] =
    basicRequest
      .body(requestBody)
      .post(apiUrl)
      .response(asJson[JsonRpcResponse[B]])
      .readTimeout(timeout)

  private def sendRequest[RQ: Writes, RS: Reads](request: RpcRequest[RS], retriesLeft: Int): Either[String, Option[RS]] = {
    def retryIf(cond: Boolean, elseError: String): Either[String, Option[RS]] =
      if (cond) {
        val retries = retriesLeft - 1
        // TODO: make non-blocking waiting
        Thread.sleep(config.apiRequestRetryWaitTime.toMillis)
        sendRequest(request, retries)
      } else Left(elseError)

    Try {
      request.send(backend).body match {
        case Left(HttpError(body, statusCode)) =>
          retryIf(retriesLeft > 0, s"server returned error $body with status ${statusCode.code}")
        case Left(DeserializationException(body, error)) => Left(s"failed to parse response $body: $error")
        case Right(JsonRpcResponse(_, Some(error))) =>
          retryIf(isTimedOut(error.message) && retriesLeft > 0, s"JSON-RPC error: ${error.message}")
        case Right(JsonRpcResponse(value, _)) => Right(value)
      }
    } match {
      case Success(result) => result
      case Failure(ex)     => retryIf(retriesLeft > 0, s"Error sending Engine API request: ${ex.toString}")
    }
  }

  private def isTimedOut(message: String): Boolean = {
    val lcErrorMessage = message.toLowerCase // Each EC has own error codes for timeouts, use a message instead
    // besu: https://github.com/hyperledger/besu/blob/main/ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/response/RpcErrorType.java#L115
    lcErrorMessage.contains("timeout") ||
    // geth: // https://github.com/ethereum/go-ethereum/blob/master/rpc/errors.go#L71
    lcErrorMessage.contains("timed out")
  }
}

object JsonRpcClient {
  val DefaultTimeout: FiniteDuration = 1.minute
}
