package units

import units.docker.EcContainer
import units.test.TestEnvironment

import scala.sys.process.{Process, ProcessLogger}

class AssetRegistryTestSuite extends BaseDockerTestSuite {
  private val clRecipient = clRichAccount1
  private val elSender    = elRichAccount1

  private val userAmount = 1
  private val elAmount   = UnitsConvert.toAtomic(userAmount, 18)

  private var activationEpoch = 0

  "WAVES and issued asset are not registered before activation" in {
    standardBridge.isRegistered(TErc20Address) shouldBe false
    standardBridge.isRegistered(WWavesAddress) shouldBe false
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
    standardBridge.isRegistered(WWavesAddress) shouldBe true
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Prepare: deploy contracts on EL")
    Process(
      s"forge script -vvvv scripts/Deployer.s.sol:Deployer --private-key $elRichAccount1PrivateKey --fork-url http://localhost:${ec1.rpcPort} --broadcast",
      TestEnvironment.ContractsDir,
      "CHAIN_ID" -> EcContainer.ChainId.toString
    ).!(ProcessLogger(out => log.info(out), err => log.error(err)))
    chainContract.waitForHeight(chainContract.getLastBlockMeta(0).value.height + 1)

    step("Enable token transfers")
    activationEpoch = waves1.api.height() + 1
    waves1.api.broadcast(
      ChainContract.enableTokenTransfers(
        StandardBridgeAddress,
        WWavesAddress,
        activationEpoch = activationEpoch
      )
    )
  }
}
