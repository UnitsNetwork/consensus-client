package units

import org.scalatest.{Args, Status, SuiteMixin}

trait ReportingTestName extends SuiteMixin {
  self: BaseItTestSuite =>

  abstract override protected def runTest(testName: String, args: Args): Status = {
    testStep(s"Test '$testName' started")
    val r = super.runTest(testName, args)
    testStep(s"Test '$testName' ${if (r.succeeds()) "SUCCEEDED" else "FAILED"}")
    r
  }

  private def testStep(text: String): Unit = step(s"---------- $text ----------")

  protected def step(text: String): Unit = log.debug(text)
}
