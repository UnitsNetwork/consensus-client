package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.transaction.TxHelpers
import units.ELUpdater.State.ChainStatus.{FollowingChain, WaitForNewChain}
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.engine.model.{ExecutionPayload, GetLogsResponseEntry}
import units.eth.EthAddress
import units.util.HexBytesConverter

class BlockFullValidationTestSuite extends BaseIntegrationTestSuite {
  private val transferEvents          = List(Bridge.ElSentNativeEvent(TxHelpers.defaultAddress, 1))
  private val blockLogs               = transferEvents.map(getLogsResponseEntry)
  private val e2CTransfersRootHashHex = HexBytesConverter.toHex(Bridge.mkTransfersHash(blockLogs).explicitGet())

  private val reliable    = ElMinerSettings(TxHelpers.signer(1))
  private val malfunction = ElMinerSettings(TxHelpers.signer(2)) // Prevents a block finalization

  override protected val defaultSettings: TestSettings = TestSettings.Default.copy(
    initialMiners = List(reliable, malfunction)
  )

  "Full validation when the block is available on EL and CL" - {
    "doesn't happen for finalized blocks" in withExtensionDomain(defaultSettings.copy(initialMiners = List(reliable))) { d =>
      step("Start new epoch for payload")
      d.advanceNewBlocks(reliable.address)
      val payload = d.createPayloadBuilder("0", reliable).buildAndSetLogs(blockLogs)
      d.advanceConsensusLayerChanged()

      step(s"Receive block ${payload.hash} payload from a peer")
      d.receivePayload(payload, reliable.account)
      d.triggerScheduledTasks()

      step(s"Append a CL micro block with block ${payload.hash} confirmation")
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(reliable.account, payload))
      d.advanceConsensusLayerChanged()

      withClue("Validation doesn't happen:") {
        d.ecClients.fullValidatedBlocks shouldBe empty
      }

      d.waitForWorking("Block considered validated and following") { s =>
        val vs = s.fullValidationStatus
        vs.lastValidatedBlock.hash shouldBe payload.hash
        vs.lastElWithdrawalIndex shouldBe empty

        is[FollowingChain](s.chainStatus)
      }
    }

    "happens for not finalized blocks" - {
      "successful validation updates the chain information" in withExtensionDomain() { d =>
        step("Start new epoch for payload")
        d.advanceNewBlocks(reliable.address)
        val payload = d.createPayloadBuilder("0", reliable).buildAndSetLogs(blockLogs)
        d.advanceConsensusLayerChanged()

        step(s"Receive block ${payload.hash} payload from a peer")
        d.receivePayload(payload, reliable.account)
        d.triggerScheduledTasks()

        step(s"Append a CL micro block with block ${payload.hash} confirmation")
        d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(reliable.account, payload, e2CTransfersRootHashHex))
        d.advanceConsensusLayerChanged()

        d.waitForCS[FollowingChain]("Following chain") { _ => }

        withClue("Validation happened:") {
          d.ecClients.fullValidatedBlocks shouldBe Set(payload.hash)
        }

        d.waitForWorking("Block considered validated") { s =>
          val vs = s.fullValidationStatus
          vs.lastValidatedBlock.hash shouldBe payload.hash
          vs.lastElWithdrawalIndex.value shouldBe -1L
        }
      }

      "unsuccessful causes a fork" - {
        def e2CTest(
            blockLogs: List[GetLogsResponseEntry],
            e2CTransfersRootHashHex: String,
            badBlockPayloadPostProcessing: ExecutionPayload => ExecutionPayload = identity
        ): Unit = withExtensionDomain() { d =>
          step("Start new epoch for payload1")
          d.advanceNewBlocks(malfunction.address)
          d.advanceConsensusLayerChanged()

          val payload1 = d.createPayloadBuilder("0", malfunction).buildAndSetLogs()
          d.ecClients.addKnown(payload1)
          d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(malfunction.account, payload1))
          d.advanceConsensusLayerChanged()

          step("Start new epoch for payload2")
          d.advanceNewBlocks(malfunction.address)
          d.advanceConsensusLayerChanged()

          val payload2 =
            badBlockPayloadPostProcessing(d.createPayloadBuilder("0-0", malfunction, payload1).rewardPrevMiner().buildAndSetLogs(blockLogs))

          step(s"Append a CL micro block with block2 ${payload2.hash} confirmation")
          d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(malfunction.account, payload2, e2CTransfersRootHashHex))
          d.advanceConsensusLayerChanged()

          step(s"Receive block2 ${payload2.hash} payload2 from a peer")
          d.receivePayload(payload2, malfunction.account)
          d.triggerScheduledTasks()

          d.waitForCS[WaitForNewChain]("Forking") { cs =>
            cs.chainSwitchInfo.referenceBlock.hash shouldBe payload1.hash
          }
        }

        "CL confirmation without a transfers root hash" in e2CTest(
          blockLogs = blockLogs,
          e2CTransfersRootHashHex = EmptyE2CTransfersRootHashHex
        )

        "Events from an unexpected EL bridge address" in {
          val fakeBridgeAddress = EthAddress.unsafeFrom("0x53481054Ad294207F6ed4B6C2E6EaE34E1Bb8704")
          val block2Logs        = transferEvents.map(x => getLogsResponseEntry(x).copy(address = fakeBridgeAddress))
          e2CTest(
            blockLogs = block2Logs,
            e2CTransfersRootHashHex = e2CTransfersRootHashHex
          )
        }

        "Different miners in CL and EL" in e2CTest(
          blockLogs = blockLogs,
          e2CTransfersRootHashHex = e2CTransfersRootHashHex,
          badBlockPayloadPostProcessing = _.copy(feeRecipient = reliable.elRewardAddress)
        )
      }
    }
  }

}
