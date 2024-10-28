package units.http

import com.wavesplatform.utils.ScorexLogging
import okhttp3.{Interceptor, Request, Response}

import java.util.concurrent.ThreadLocalRandom
import scala.util.Try

object OkHttpLogger extends Interceptor with ScorexLogging {
  override def intercept(chain: Interceptor.Chain): Response = {
    val currRequestId = ThreadLocalRandom.current().nextInt(10000, 100000).toString
    val req           = chain.request()
    log.debug(s"[$currRequestId] ${req.url()} ${readRequestBody(req)}")
    val res = chain.proceed(req)
    log.debug(s"[$currRequestId] ${res.code()}: ${readResponseBody(res)}")
    res
  }

  private def readRequestBody(request: Request) = request.body() match {
    case null => "null"
    case body =>
      val buffer = new okio.Buffer()
      Try {
        body.writeTo(buffer)
        buffer.readUtf8()
      }.getOrElse("Could not read body")
  }

  private def readResponseBody(response: Response) = response.body() match {
    case null => "null"
    case body =>
      val source = body.source()
      source.request(Long.MaxValue) // Buffer the entire body.
      val buffer = source.buffer().clone()
      buffer.readUtf8()
  }
}
