package units

import com.wavesplatform.common.utils.EitherExt2
import units.ELUpdater.State
import units.ELUpdater.State.ChainStatus.{FollowingChain, WaitForNewChain}
import units.ELUpdater.State.Working
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyElToClTransfersRootHashHex
import units.client.http.model.GetLogsResponseEntry
import units.eth.EthAddress
import units.util.HexBytesConverter
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet

class BlockFullValidationTestSuite extends BaseIntegrationTestSuite {
  private val transferEvents             = List(Bridge.ElSentNativeEvent(TxHelpers.defaultAddress, 1))
  private val ecBlockLogs                = transferEvents.map(getLogsResponseEntry)
  private val elToClTransfersRootHashHex = HexBytesConverter.toHex(Bridge.mkTransfersHash(ecBlockLogs).explicitGet())

  private val reliable    = ElMinerSettings(Wallet.generateNewAccount(TestSettings.Default.walletSeed, 0))
  private val malfunction = ElMinerSettings(TxHelpers.signer(2)) // Prevents a block finalization

  override protected val defaultSettings: TestSettings = TestSettings.Default.copy(
    initialMiners = List(reliable, malfunction)
  )

  "Full validation when the block is available on EL and CL" - {
    "doesn't happen for finalized blocks" in withConsensusClient(defaultSettings.copy(initialMiners = List(reliable))) { (d, c) =>
      val ecBlock = d.createNextEcBlock(
        hash = d.createBlockHash("0"),
        parent = d.ecGenesisBlock,
        minerRewardL2Address = reliable.elRewardAddress
      )

      d.ecClients.setBlockLogs(ecBlock.hash, Bridge.ElSentNativeEventTopic, ecBlockLogs)

      step("Start new epoch for ecBlock")
      d.advanceNewBlocks(reliable.address)
      d.advanceElu()

      step(s"Receive ecBlock ${ecBlock.hash} from a peer")
      d.receiveNetworkBlock(ecBlock, reliable.account)
      d.triggerScheduledTasks()

      step(s"Append a CL micro block with ecBlock ${ecBlock.hash} confirmation")
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(reliable.account, ecBlock, d.blockchain.height))
      d.advanceElu()

      withClue("Validation doesn't happen:") {
        d.ecClients.fullValidatedBlocks shouldBe empty
      }

      withClue("Block considered validated:") {
        val s = is[Working[?]](c.elu.state)
        is[FollowingChain](s.chainStatus)

        val vs = s.fullValidationStatus
        vs.validated should contain(ecBlock.hash)
        vs.lastElWithdrawalIndex shouldBe empty
      }
    }

    "happens for not finalized blocks" - {
      "successful validation updates the chain information" in withConsensusClient() { (d, c) =>
        val ecBlock = d.createNextEcBlock(
          hash = d.createBlockHash("0"),
          parent = d.ecGenesisBlock,
          minerRewardL2Address = reliable.elRewardAddress
        )

        d.ecClients.setBlockLogs(ecBlock.hash, Bridge.ElSentNativeEventTopic, ecBlockLogs)

        step("Start new epoch for ecBlock")
        d.advanceNewBlocks(reliable.address)
        d.advanceElu()

        step(s"Receive ecBlock ${ecBlock.hash} from a peer")
        d.receiveNetworkBlock(ecBlock, reliable.account)
        d.triggerScheduledTasks()

        step(s"Append a CL micro block with ecBlock ${ecBlock.hash} confirmation")
        d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(reliable.account, ecBlock, d.blockchain.height, elToClTransfersRootHashHex))
        d.advanceElu()

        withClue("Expected state:") {
          val s = is[Working[?]](c.elu.state)
          is[FollowingChain](s.chainStatus)
        }

        withClue("Validation happened:") {
          d.ecClients.fullValidatedBlocks shouldBe Set(ecBlock.hash)
        }

        withClue("Block considered validated:") {
          val s = is[State.Working[?]](c.elu.state).fullValidationStatus
          s.validated should contain(ecBlock.hash)
          s.lastElWithdrawalIndex.value shouldBe -1L
        }
      }

      "unsuccessful causes a fork" - {
        def elToClTest(
            blockLogs: List[GetLogsResponseEntry],
            elToClTransfersRootHashHex: String
        ): Unit = withConsensusClient() { (d, c) =>
          step("Start new epoch for ecBlock1")
          d.advanceNewBlocks(malfunction.address)
          d.advanceElu()

          val ecBlock1 = d.createNextEcBlock(
            hash = d.createBlockHash("0"),
            parent = d.ecGenesisBlock,
            minerRewardL2Address = malfunction.elRewardAddress
          )
          d.ecClients.setBlockLogs(ecBlock1.hash, Bridge.ElSentNativeEventTopic, Nil)
          d.ecClients.addKnown(ecBlock1)
          d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(malfunction.account, ecBlock1, d.blockchain.height))
          d.advanceElu()

          step("Start new epoch for ecBlock2")
          d.advanceNewBlocks(malfunction.address)
          d.advanceBlockDelay()

          val ecBlock2 = d.createNextEcBlock(
            hash = d.createBlockHash("0-0"),
            parent = ecBlock1,
            minerRewardL2Address = malfunction.elRewardAddress,
            withdrawals = Vector(d.createWithdrawal(0, malfunction.elRewardAddress))
          )
          d.ecClients.setBlockLogs(ecBlock2.hash, Bridge.ElSentNativeEventTopic, blockLogs)

          step(s"Receive ecBlock2 ${ecBlock2.hash} from a peer")
          d.receiveNetworkBlock(ecBlock2, malfunction.account)
          d.triggerScheduledTasks()

          step(s"Append a CL micro block with ecBlock2 ${ecBlock2.hash} confirmation")
          d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(malfunction.account, ecBlock2, d.blockchain.height, elToClTransfersRootHashHex))
          d.advanceElu()

          withClue("Validation happened:") {
            d.ecClients.fullValidatedBlocks should contain(ecBlock2.hash)
          }

          withClue("Forking:") {
            val s = is[Working[?]](c.elu.state)
            s.fullValidationStatus.validated should contain(ecBlock2.hash)
            s.fullValidationStatus.lastElWithdrawalIndex shouldBe empty

            is[WaitForNewChain](s.chainStatus)
          }
        }

        "CL confirmation without a transfers root hash" in elToClTest(
          blockLogs = ecBlockLogs,
          elToClTransfersRootHashHex = EmptyElToClTransfersRootHashHex
        )

        "Events from an unexpected EL bridge address" in {
          val fakeBridgeAddress = EthAddress.unsafeFrom("0x53481054Ad294207F6ed4B6C2E6EaE34E1Bb8704")
          val ecBlock2Logs      = transferEvents.map(x => getLogsResponseEntry(x).copy(address = fakeBridgeAddress))
          elToClTest(
            blockLogs = ecBlock2Logs,
            elToClTransfersRootHashHex = elToClTransfersRootHashHex
          )
        }
      }
    }
  }

}
