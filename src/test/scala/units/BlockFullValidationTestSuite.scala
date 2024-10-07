package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.transaction.TxHelpers
import units.ELUpdater.State.ChainStatus.{FollowingChain, WaitForNewChain}
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.engine.model.{EcBlock, GetLogsResponseEntry}
import units.eth.EthAddress
import units.util.HexBytesConverter

class BlockFullValidationTestSuite extends BaseIntegrationTestSuite {
  private val transferEvents          = List(Bridge.ElSentNativeEvent(TxHelpers.defaultAddress, 1))
  private val ecBlockLogs             = transferEvents.map(getLogsResponseEntry)
  private val e2CTransfersRootHashHex = HexBytesConverter.toHex(Bridge.mkTransfersHash(ecBlockLogs).explicitGet())

  private val reliable    = ElMinerSettings(TxHelpers.signer(1))
  private val malfunction = ElMinerSettings(TxHelpers.signer(2)) // Prevents a block finalization

  override protected val defaultSettings: TestSettings = TestSettings.Default.copy(
    initialMiners = List(reliable, malfunction)
  )

  "Full validation when the block is available on EL and CL" - {
    "L2-315 doesn't happen for finalized blocks" in withExtensionDomain(defaultSettings.copy(initialMiners = List(reliable))) { d =>
      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(reliable.address)//ожидание эпохи майнера -сайнер1
      val ecBlock = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(ecBlockLogs)
      d.advanceConsensusLayerChanged()

      step(s"Receive ecBlock ${ecBlock.hash} from a peer")
      d.receiveNetworkBlock(ecBlock, reliable.account)
      d.triggerScheduledTasks()

      step(s"Append a CL micro block with ecBlock ${ecBlock.hash} confirmation")
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(reliable.account, ecBlock))
      d.advanceConsensusLayerChanged()

      withClue("Validation doesn't happen:") {
        d.ecClients.fullValidatedBlocks shouldBe empty
      }

      d.waitForWorking("Block considered validated and following") { s =>
        val vs = s.fullValidationStatus
        vs.lastValidatedBlock.hash shouldBe ecBlock.hash
        vs.lastElWithdrawalIndex shouldBe empty

        is[FollowingChain](s.chainStatus)
      }
    }

    "L2-310 happens for not finalized blocks" - {
      "successful validation updates the chain information" in withExtensionDomain() { d =>
        step("Start new epoch for ecBlock")
        d.advanceNewBlocks(reliable.address)
        val ecBlock = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(ecBlockLogs)
        d.advanceConsensusLayerChanged()

        step(s"Receive ecBlock ${ecBlock.hash} from a peer")
        d.receiveNetworkBlock(ecBlock, reliable.account)
        d.triggerScheduledTasks()

        step(s"Append a CL micro block with ecBlock ${ecBlock.hash} confirmation")
        d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(reliable.account, ecBlock, e2CTransfersRootHashHex))
        d.advanceConsensusLayerChanged()

        d.waitForCS[FollowingChain]("Following chain") { _ => }

        withClue("Validation happened:") {
          d.ecClients.fullValidatedBlocks shouldBe Set(ecBlock.hash)
        }

        d.waitForWorking("Block considered validated") { s =>
          val vs = s.fullValidationStatus
          vs.lastValidatedBlock.hash shouldBe ecBlock.hash
          vs.lastElWithdrawalIndex.value shouldBe -1L
        }
      }

      "L2-245 unsuccessful causes a fork" - {
        def e2CTest(
            blockLogs: List[GetLogsResponseEntry],
            e2CTransfersRootHashHex: String,
            badBlockPostProcessing: EcBlock => EcBlock = identity
        ): Unit = withExtensionDomain() { d =>
          step("Start new epoch for ecBlock1")
          d.advanceNewBlocks(malfunction.address)
          d.advanceConsensusLayerChanged()

          val ecBlock1 = d.createEcBlockBuilder("0", malfunction).buildAndSetLogs()
          d.ecClients.addKnown(ecBlock1)
          d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(malfunction.account, ecBlock1))
          d.advanceConsensusLayerChanged()

          step("Start new epoch for ecBlock2")
          d.advanceNewBlocks(malfunction.address)
          d.advanceConsensusLayerChanged()

          val ecBlock2 = badBlockPostProcessing(d.createEcBlockBuilder("0-0", malfunction, ecBlock1).rewardPrevMiner().buildAndSetLogs(blockLogs))

          step(s"Append a CL micro block with ecBlock2 ${ecBlock2.hash} confirmation")
          d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(malfunction.account, ecBlock2, e2CTransfersRootHashHex))
          d.advanceConsensusLayerChanged()

          step(s"Receive ecBlock2 ${ecBlock2.hash} from a peer")
          d.receiveNetworkBlock(ecBlock2, malfunction.account)
          d.triggerScheduledTasks()

          d.waitForCS[WaitForNewChain]("Forking") { cs =>
            cs.chainSwitchInfo.referenceBlock.hash shouldBe ecBlock1.hash
          }

        }

        "L2-361 CL confirmation without a transfers root hash" in e2CTest(
          blockLogs = ecBlockLogs,
          e2CTransfersRootHashHex = EmptyE2CTransfersRootHashHex
        )

        "L2-385 Events from an unexpected EL bridge address" in {
          val fakeBridgeAddress = EthAddress.unsafeFrom("0x53481054Ad294207F6ed4B6C2E6EaE34E1Bb8704")
          val ecBlock2Logs      = transferEvents.map(x => getLogsResponseEntry(x).copy(address = fakeBridgeAddress))
          e2CTest(
            blockLogs = ecBlock2Logs,
            e2CTransfersRootHashHex = e2CTransfersRootHashHex
          )
        }

        "L2-256 Different miners in CL and EL" in e2CTest(
          blockLogs = ecBlockLogs,
          e2CTransfersRootHashHex = e2CTransfersRootHashHex,
          badBlockPostProcessing = _.copy(minerRewardL2Address = reliable.elRewardAddress)
        )
      }
    }
  }

}
