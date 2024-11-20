package units

class RegistryDockerTestSuite extends BaseDockerTestSuite {
  "Approved, rejected, approved - mining works when approved" in {
    step(s"Wait miner 1 (${miner11Account.toAddress}) forge at least one block")
    chainContract.waitForHeight(1L)

    step("Broadcast a reject transaction")
    val rejectTxnHeight = waves1.api.broadcastAndWait(ChainRegistry.reject()).height

    step("Wait a rejection height")
    waves1.api.waitForHeight(rejectTxnHeight + 1)
    val lastElBlock1 = chainContract.getLastBlockMeta(0L).value

    step("Expect no mining")
    waves1.api.waitForHeight(rejectTxnHeight + 2)

    val lastElBlock2 = chainContract.getLastBlockMeta(0L).value
    withClue("Same block - no mining: ") {
      lastElBlock2.hash shouldBe lastElBlock1.hash
    }

    val approveTxnHeight = waves1.api.broadcastAndWait(ChainRegistry.approve()).height
    step("Wait an approval height")
    waves1.api.waitForHeight(approveTxnHeight + 1)

    step("Mining working")
    chainContract.waitForHeight(lastElBlock2.height + 1)
  }
}
