package units

import com.wavesplatform.transaction.TxHelpers
import units.ELUpdater.State.Working
import units.eth.EthAddress

class BlockBriefValidationTestSuite extends BaseIntegrationTestSuite {
  private val miner = ElMinerSettings(TxHelpers.signer(1))

  override protected val defaultSettings: TestSettings = TestSettings.Default.copy(
    initialMiners = List(miner)
  )

  "Brief validation of network block" - {
    "accepts if it is valid" in test { d =>
      val payload = d.createPayloadBuilder("0", miner).build()

      step(s"Receive network block ${payload.hash} with payload from a peer")
      d.receiveNetworkBlock(payload, miner.account)
      withClue("Brief block validation:") {
        d.triggerScheduledTasks()
        d.pollSentNetworkBlock() match {
          case Some(sent) => sent.hash shouldBe payload.hash
          case None       => fail(s"${payload.hash} should not be ignored")
        }
      }
    }

    "otherwise ignoring" in test { d =>
      val payload = d.createPayloadBuilder("0", minerRewardAddress = EthAddress.empty, parentPayload = d.genesisBlockPayload).build()

      step(s"Receive network block ${payload.hash} with payload from a peer")
      d.receiveNetworkBlock(payload, miner.account)
      withClue("Brief block validation:") {
        d.triggerScheduledTasks()
        if (d.pollSentNetworkBlock().nonEmpty) fail(s"${payload.hash} should be ignored, because it is invalid by brief validation rules")
      }
    }
  }

  private def test(f: ExtensionDomain => Unit): Unit = withExtensionDomain() { d =>
    step("Start a new epoch")
    d.advanceNewBlocks(miner.address)
    d.advanceConsensusLayerChanged()

    is[Working[?]](d.consensusClient.elu.state)

    f(d)
  }
}
