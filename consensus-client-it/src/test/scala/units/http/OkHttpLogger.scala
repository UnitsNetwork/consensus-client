package units.http

import com.wavesplatform.api.LoggingUtil
import com.wavesplatform.utils.ScorexLogging
import okhttp3.{Interceptor, Request, Response}
import play.api.libs.json.Json
import units.el.ElBridgeClient

import scala.util.Try

object OkHttpLogger extends Interceptor with ScorexLogging {
  override def intercept(chain: Interceptor.Chain): Response = {
    val req     = chain.request()
    val bodyStr = readRequestBody(req)

    var attempt       = 1
    var currRequestId = (Json.parse(bodyStr) \ "id").asOpt[Long].getOrElse(LoggingUtil.currRequestId.toLong)
    if (ElBridgeClient.idHasAttempt(currRequestId)) {
      attempt = ElBridgeClient.attempt(currRequestId)
      currRequestId = ElBridgeClient.baseId(currRequestId)
    }

    if (attempt == 1) log.debug(s"[$currRequestId] ${req.method()} ${req.url()}: body=$bodyStr") // HACK: Log only the first attempt
    val res = chain.proceed(req)
    log.debug(s"[$currRequestId] HTTP ${res.code()}: body=${readResponseBody(res)}")
    res
  }

  private def readRequestBody(request: Request): String = request.body() match {
    case null => "null"
    case body =>
      val buffer = new okio.Buffer()
      Try {
        body.writeTo(buffer)
        buffer.readUtf8()
      }.getOrElse("Could not read body")
  }

  private def readResponseBody(response: Response): String = response.body() match {
    case null => "null"
    case body =>
      val source = body.source()
      source.request(Long.MaxValue) // Buffer the entire body.
      val buffer = source.getBuffer.clone()
      buffer.readUtf8()
  }
}
