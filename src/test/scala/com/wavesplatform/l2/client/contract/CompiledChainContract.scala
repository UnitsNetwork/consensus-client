package units.client.contract

import com.wavesplatform.common.utils.{Base64, EitherExt2}
import com.wavesplatform.lang.API
import com.wavesplatform.lang.script.Script
import com.wavesplatform.lang.v1.estimator.v3.ScriptEstimatorV3

import java.nio.charset.StandardCharsets
import scala.util.Using

object CompiledChainContract {
  private val chainContractSrcBytes = Using(getClass.getResourceAsStream("/main.ride"))(_.readAllBytes()).get
  private val chainContractSrc      = new String(chainContractSrcBytes, StandardCharsets.UTF_8)

  val script = API
    .compile(
      input = chainContractSrc,
      estimator = ScriptEstimatorV3.latest,
      needCompaction = true,
      libraries = Map.empty
    )
    .flatMap(x => Script.fromBase64String(Base64.encode(x.bytes)))
    .explicitGet()
}
