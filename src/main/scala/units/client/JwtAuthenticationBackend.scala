package units.client

import units.util.HexBytesConverter
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtJson}
import sttp.capabilities.Effect
import sttp.client3.{DelegateSttpBackend, Request, Response, SttpBackend}

import java.time.Clock
import javax.crypto.spec.SecretKeySpec

class JwtAuthenticationBackend[F[_], P](jwtSecret: String, delegate: SttpBackend[F, P]) extends DelegateSttpBackend[F, P](delegate) {
  private implicit val clock: Clock = Clock.systemUTC
  private val secretKey             = new SecretKeySpec(HexBytesConverter.toBytes(jwtSecret), JwtAlgorithm.HS256.fullName)
  override def send[T, R >: P & Effect[F]](request: Request[T, R]): F[Response[T]] = {
    delegate.send(request.auth.bearer(JwtJson.encode(JwtClaim().issuedNow, secretKey, JwtAlgorithm.HS256)))
  }
}
