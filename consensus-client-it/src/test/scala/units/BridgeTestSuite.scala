package units

class BridgeTestSuite extends BaseItTestSuite {
  "L2-379 Checking balances in EL->CL transfers" in {
    val sendResult = ec1.elBridge.sendNative(elRichAccount1, clRichAccount1.toAddress, BigInt("1000000000000000000"))
    Thread.sleep(60000)

//    waves1.api.broadcastAndWait(
//      chainContract.withdraw(
//      )
//    )
  }

  "L2-380 Checking balances in CL->EL transfers" in {}
}
