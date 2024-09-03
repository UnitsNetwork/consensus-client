package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.transaction.TxHelpers
import units.ELUpdater.State.ChainStatus.{FollowingChain, WaitForNewChain}
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.http.model.{EcBlock, GetLogsResponseEntry}
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
    "doesn't happen for finalized blocks" in withConsensusClient2(defaultSettings.copy(initialMiners = List(reliable))) { d =>
      val ecBlock = d.createNextEcBlock(
        hash = d.createBlockHash("0"),
        parent = d.ecGenesisBlock,
        minerRewardL2Address = reliable.elRewardAddress
      )

      d.ecClients.setBlockLogs(ecBlock.hash, Bridge.ElSentNativeEventTopic, ecBlockLogs)

      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(reliable.address)
      d.advanceConsensusLayerChanged()

      step(s"Receive ecBlock ${ecBlock.hash} from a peer")
      d.receiveNetworkBlock(ecBlock, reliable.account)
      d.triggerScheduledTasks()

      step(s"Append a CL micro block with ecBlock ${ecBlock.hash} confirmation")
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(reliable.account, ecBlock, d.blockchain.height))
      d.advanceConsensusLayerChanged()

      withClue("Validation doesn't happen:") {
        d.ecClients.fullValidatedBlocks shouldBe empty
      }

      waitForWorking(d, d.consensusClient, "Block considered validated and following") { s =>
        val vs = s.fullValidationStatus
        vs.validated should contain(ecBlock.hash)
        vs.lastElWithdrawalIndex shouldBe empty

        is[FollowingChain](s.chainStatus)
      }
    }

    "happens for not finalized blocks" - {
      "successful validation updates the chain information" in withConsensusClient2() { d =>
        val ecBlock = d.createNextEcBlock(
          hash = d.createBlockHash("0"),
          parent = d.ecGenesisBlock,
          minerRewardL2Address = reliable.elRewardAddress
        )

        d.ecClients.setBlockLogs(ecBlock.hash, Bridge.ElSentNativeEventTopic, ecBlockLogs)

        step("Start new epoch for ecBlock")
        d.advanceNewBlocks(reliable.address)
        d.advanceConsensusLayerChanged()

        step(s"Receive ecBlock ${ecBlock.hash} from a peer")
        d.receiveNetworkBlock(ecBlock, reliable.account)
        d.triggerScheduledTasks()

        step(s"Append a CL micro block with ecBlock ${ecBlock.hash} confirmation")
        d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(reliable.account, ecBlock, d.blockchain.height, e2CTransfersRootHashHex))
        d.advanceConsensusLayerChanged()

        waitForCS[FollowingChain](d, d.consensusClient, "Following chain") { _ => }

        withClue("Validation happened:") {
          d.ecClients.fullValidatedBlocks shouldBe Set(ecBlock.hash)
        }

        waitForWorking(d, d.consensusClient, "Block considered validated") { s =>
          val vs = s.fullValidationStatus
          vs.validated should contain(ecBlock.hash)
          vs.lastElWithdrawalIndex.value shouldBe -1L
        }
      }

      "unsuccessful causes a fork" - {
        def e2CTest(
            blockLogs: List[GetLogsResponseEntry],
            e2CTransfersRootHashHex: String,
            badBlockPostProcessing: EcBlock => EcBlock = identity
        ): Unit = withConsensusClient2() { d =>
          step("Start new epoch for ecBlock1")
          d.advanceNewBlocks(malfunction.address)
          d.advanceConsensusLayerChanged()

          val ecBlock1 = d.createNextEcBlock(
            hash = d.createBlockHash("0"),
            parent = d.ecGenesisBlock,
            minerRewardL2Address = malfunction.elRewardAddress
          )
          d.ecClients.setBlockLogs(ecBlock1.hash, Bridge.ElSentNativeEventTopic, Nil)
          d.ecClients.addKnown(ecBlock1)
          d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(malfunction.account, ecBlock1, d.blockchain.height))
          d.advanceConsensusLayerChanged()

          step("Start new epoch for ecBlock2")
          d.advanceNewBlocks(malfunction.address)
          d.advanceBlockDelay() // TODO

          val ecBlock2 = badBlockPostProcessing(
            d.createNextEcBlock(
              hash = d.createBlockHash("0-0"),
              parent = ecBlock1,
              minerRewardL2Address = malfunction.elRewardAddress,
              withdrawals = Vector(d.createWithdrawal(0, malfunction.elRewardAddress))
            )
          )
          d.ecClients.setBlockLogs(ecBlock2.hash, Bridge.ElSentNativeEventTopic, blockLogs)

          step(s"Append a CL micro block with ecBlock2 ${ecBlock2.hash} confirmation")
          d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(malfunction.account, ecBlock2, d.blockchain.height, e2CTransfersRootHashHex))
          d.advanceConsensusLayerChanged()

          step(s"Receive ecBlock2 ${ecBlock2.hash} from a peer")
          d.receiveNetworkBlock(ecBlock2, malfunction.account)
          d.triggerScheduledTasks()

          waitForWorking(d, d.consensusClient, "Block considered validated and forking") { s =>
            val vs = s.fullValidationStatus
            vs.validated should contain(ecBlock2.hash)
            vs.lastElWithdrawalIndex shouldBe empty

            is[WaitForNewChain](s.chainStatus)
          }
        }

        "CL confirmation without a transfers root hash" in e2CTest(
          blockLogs = ecBlockLogs,
          e2CTransfersRootHashHex = EmptyE2CTransfersRootHashHex
        )

        "Events from an unexpected EL bridge address" in {
          val fakeBridgeAddress = EthAddress.unsafeFrom("0x53481054Ad294207F6ed4B6C2E6EaE34E1Bb8704")
          val ecBlock2Logs      = transferEvents.map(x => getLogsResponseEntry(x).copy(address = fakeBridgeAddress))
          e2CTest(
            blockLogs = ecBlock2Logs,
            e2CTransfersRootHashHex = e2CTransfersRootHashHex
          )
        }

        "Different miners in CL and EL" in e2CTest(
          blockLogs = ecBlockLogs,
          e2CTransfersRootHashHex = e2CTransfersRootHashHex,
          badBlockPostProcessing = _.copy(minerRewardL2Address = reliable.elRewardAddress)
        )
      }
    }
  }

}
