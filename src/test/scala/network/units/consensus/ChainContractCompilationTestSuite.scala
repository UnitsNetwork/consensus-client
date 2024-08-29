package network.units.consensus

import com.wavesplatform.common.utils.{Base64, EitherExt2}
import com.wavesplatform.lang.API
import com.wavesplatform.lang.contract.DApp
import com.wavesplatform.lang.script.Script
import com.wavesplatform.lang.v1.estimator.v3.ScriptEstimatorV3
import com.wavesplatform.test.BaseSuite
import org.scalatest.freespec.AnyFreeSpec

import java.nio.charset.StandardCharsets
import scala.util.Using

class ChainContractCompilationTestSuite extends AnyFreeSpec with BaseSuite {
  "main.ride should be compiled" in {
    val chainContractSrcBytes = Using(getClass.getResourceAsStream("/main.ride"))(_.readAllBytes()).get
    val chainContractSrc      = new String(chainContractSrcBytes, StandardCharsets.UTF_8)

    val r = API
      .compile(
        input = chainContractSrc,
        estimator = ScriptEstimatorV3.latest,
        libraries = Map.empty
      )
      .flatMap(x => Script.fromBase64String(Base64.encode(x.bytes)))
      .explicitGet()

    r.expr shouldBe a[DApp]
  }
}
