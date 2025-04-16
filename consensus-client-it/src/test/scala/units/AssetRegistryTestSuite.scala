package units

class AssetRegistryTestSuite extends BaseDockerTestSuite {
  private val clRecipient = clRichAccount1
  private val elSender    = elRichAccount1

  private val userAmount = 1
  private val elAmount   = UnitsConvert.toAtomic(userAmount, 18)

  private var activationEpoch = 0

  "WAVES and issued asset are not registered before activation" in {
    standardBridge.isRegistered(TErc20Address, ignoreExceptions = true) shouldBe false
    standardBridge.isRegistered(WWavesAddress, ignoreExceptions = true) shouldBe false
  }

  "Can't transfer issued asset without a registration" in {
    waves1.api.waitForHeight(activationEpoch)
    val e = standardBridge.getRevertReasonForBridgeErc20(elSender, TErc20Address, clRecipient.toAddress, elAmount)
    e should include("Token is not registered")
  }

  "WAVES is registered after activation" in {
    waves1.api.waitForHeight(activationEpoch)
    chainContract.waitForEpoch(activationEpoch)

    standardBridge.isRegistered(TErc20Address) shouldBe false
    eventually {
      standardBridge.isRegistered(WWavesAddress) shouldBe true
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    deploySolidityContracts()

    step("Enable token transfers")
    activationEpoch = waves1.api.height() + 1
    waves1.api.broadcast(
      ChainContract.enableTokenTransfersWithWaves(
        StandardBridgeAddress,
        WWavesAddress,
        activationEpoch = activationEpoch
      )
    )
  }
}
