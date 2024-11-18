package com.wavesplatform.api

import com.wavesplatform.api.LoggingBackend.{LoggingOptions, LoggingOptionsTag}
import com.wavesplatform.utils.ScorexLogging
import sttp.capabilities.Effect
import sttp.client3.*

class LoggingBackend[F[_], P](delegate: SttpBackend[F, P]) extends DelegateSttpBackend[F, P](delegate) with ScorexLogging {
  override def send[T, R >: P & Effect[F]](request: Request[T, R]): F[Response[T]] = {
    val l = request.tag(LoggingOptionsTag).collect { case l: LoggingOptions => l }

    l.filter(_.logRequest).foreach { l =>
      var logStr = s"${l.prefix} ${request.method} ${request.uri}"
      if (l.logRequestBody) logStr += s": body=${request.body.show}"
      log.debug(logStr)
    }

    val requestWithRawJson = request.response(asBothOption(request.response, asStringAlways))
    val withErrorLog = responseMonad.handleError(requestWithRawJson.send(delegate)) { x =>
      l.foreach { l => log.debug(s"${l.prefix} Error: ${x.getMessage}") }
      responseMonad.error(x)
    }

    responseMonad.flatMap(withErrorLog) { response =>
      l.foreach { l =>
        var logStr = s"${l.prefix} HTTP ${response.code}"
        if (l.logResponseBody) logStr += s": body=${response.body._2}"
        log.debug(logStr)
      }

      responseMonad.unit(response.copy(body = response.body._1))
    }
  }
}

object LoggingBackend {
  val LoggingOptionsTag = "logging"

  case class LoggingOptions(
      logCall: Boolean = true,
      logRequest: Boolean = true,
      logRequestBody: Boolean = true,
      logResponseBody: Boolean = true,
      requestId: Int = LoggingUtil.currRequestId
  ) {
    val prefix = s"[$requestId]"
  }
}
