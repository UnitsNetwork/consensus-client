package units.client

import com.wavesplatform.utils.ScorexLogging
import sttp.capabilities.Effect
import sttp.client3.*

class LoggingBackend[F[_], P](delegate: SttpBackend[F, P]) extends DelegateSttpBackend[F, P](delegate) with ScorexLogging {
  override def send[T, R >: P & Effect[F]](request: Request[T, R]): F[Response[T]] = {
    val prefix             = request.tag(RequestIdTag).fold("")(id => s"[$id] ")
    val requestWithRawJson = request.response(asBothOption(request.response, asStringAlways))

    log.trace(request.tag(RetriesLeftTag) match {
      case Some(retriesLeft) => s"${prefix}Retry, retries left: $retriesLeft"
      case None              => s"${prefix}Request: ${request.uri}, body=${request.body.show}"
    })

    val withErrorLog = responseMonad.handleError(requestWithRawJson.send(delegate)) { x =>
      log.trace(s"$prefix${x.getMessage}", x)
      responseMonad.error(x)
    }

    responseMonad.flatMap(withErrorLog) { response =>
      log.trace(s"${prefix}Response: ${response.code}, body=${response.body._2.getOrElse("empty")}")
      responseMonad.unit(response.copy(body = response.body._1))
    }
  }
}
