package units.client.contract

import com.wavesplatform.account.Address
import com.wavesplatform.api.http.utils.UtilsEvaluator
import com.wavesplatform.lang.contract.DApp
import com.wavesplatform.state.AccountScriptInfo
import com.wavesplatform.test.BaseSuite
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.utils.EmptyBlockchain
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.{BeforeAndAfterAll, EitherValues}
import play.api.libs.json.{JsDefined, JsObject, JsString, Json}
import units.client.contract.CompiledChainContract.script

class ChainContractPureTestSuite extends AnyFreeSpec with BaseSuite with BeforeAndAfterAll with EitherValues {
  private val dAppAcc  = TxHelpers.signer(0)
  private val dAppAddr = dAppAcc.toAddress

  private val testBlockchain = new EmptyBlockchain {
    override def accountScript(address: Address): Option[AccountScriptInfo] = {
      if (address == dAppAddr) Some(AccountScriptInfo(dAppAcc.publicKey, script, 0, Map.empty))
      else super.accountScript(address)
    }
  }

  "main.ride should be compiled" in {
    script.expr shouldBe a[DApp]
  }

  "setOrFail" - {
    "positive" in forAll(
      Table(
        ("origFlags", "index", "expectedResult"),
        ("", 0, "1"),
        ("", 1, "01"),
        ("0", 1, "01"),
        ("1", 2, "101"),
        ("0", 0, "1")
      )
    ) { case (origFlags, index, expectedResult) =>
      setOrFail(origFlags, index).value shouldBe expectedResult
    }

    "negative" in forAll(
      Table(
        ("origFlags", "index", "error"),
        ("", -1, "Can't withdraw at negative index"),
        ("1", 0, "Transfer #0 has been already taken"),
        ("11", 1, "Transfer #1 has been already taken"),
        ("0", 1040, "Can't add 1039 empty flags")
      )
    ) { case (origFlags, index, error) =>
      setOrFail(origFlags, index).left.value should include(error)
    }
  }

  private def setOrFail(flag: String, index: Int): Either[String, String] = {
    val evalResult = runExpr(s"""setOrFail("$flag", $index)""")
    evalResult \ "message" match {
      case JsDefined(JsString(error)) => Left(error)
      case JsDefined(x)               => throw new RuntimeException(s"Unexpected message: $x")
      case _ =>
        evalResult \ "result" \ "value" match {
          case JsDefined(JsString(r)) => Right(r)
          case x                      => throw new RuntimeException(s"Unexpected result $x")
        }
    }
  }

  private def runExpr(expr: String): JsObject = UtilsEvaluator.evaluate(
    testBlockchain,
    dAppAddr,
    Json.obj("expr" -> expr),
    UtilsEvaluator.EvaluateOptions(Int.MaxValue, 0, enableTraces = false, intAsString = false)
  )
}
