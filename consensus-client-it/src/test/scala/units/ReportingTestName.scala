package units

import org.scalatest.{Args, Status, SuiteMixin}

trait ReportingTestName extends SuiteMixin {
  self: BaseItTestSuite =>

  abstract override protected def runTest(testName: String, args: Args): Status = {
    printWrapped(s"Test '$testName' started")
    val r = super.runTest(testName, args)
    printWrapped(s"Test '$testName' ${if (r.succeeds()) "SUCCEEDED" else "FAILED"}")
    r
  }

  private def printWrapped(text: String): Unit = print(s"---------- $text ----------")

  protected def print(text: String): Unit =
    log.debug(text)
}
