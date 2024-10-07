package units

import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.mining.MultiDimensionalMiningConstraint
import com.wavesplatform.test.produce
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Event
import org.web3j.abi.datatypes.generated.Bytes20
import units.ELUpdater.State.ChainStatus.{Mining, WaitForNewChain}
import units.client.contract.HasConsensusLayerDappTxHelpers.defaultFees
import units.eth.EthAddress
import units.util.HexBytesConverter

import scala.jdk.CollectionConverters.SeqHasAsJava

class E2CTransfersTestSuite extends BaseIntegrationTestSuite {
  private val transferReceiver        = TxHelpers.secondSigner
  private val transfer                = Bridge.ElSentNativeEvent(transferReceiver.toAddress, 1)
  private val transferEvent           = getLogsResponseEntry(transfer)
  private val ecBlockLogs             = List(transferEvent)
  private val e2CTransfersRootHashHex = HexBytesConverter.toHex(Bridge.mkTransfersHash(ecBlockLogs).explicitGet())
  private val transferProofs          = Bridge.mkTransferProofs(List(transfer), 0).reverse // Contract requires from bottom to top

  private val reliable    = ElMinerSettings(Wallet.generateNewAccount(TestSettings.Default.walletSeed, 0))
  private val malfunction = ElMinerSettings(TxHelpers.signer(2)) // Prevents block finalization

  override protected val defaultSettings: TestSettings = TestSettings.Default.copy(
    initialMiners = List(reliable),
    additionalBalances = List(AddrWithBalance(transferReceiver.toAddress, defaultFees.chainContract.withdrawFee))
  )

  "Multiple withdrawals" in {
    val transferReceiver1       = transferReceiver
    val transferReceiver2       = TxHelpers.signer(2)
    val transfer1               = Bridge.ElSentNativeEvent(transferReceiver1.toAddress, 1)
    val transfer2               = Bridge.ElSentNativeEvent(transferReceiver2.toAddress, 1)
    val transferEvents          = List(transfer1, transfer2)
    val ecBlockLogs             = transferEvents.map(getLogsResponseEntry)
    val e2CTransfersRootHashHex = HexBytesConverter.toHex(Bridge.mkTransfersHash(ecBlockLogs).explicitGet())
    val transfer1Proofs         = Bridge.mkTransferProofs(transferEvents, 0).reverse
    val transfer2Proofs         = Bridge.mkTransferProofs(transferEvents, 1).reverse

    val settings = defaultSettings.copy(
      additionalBalances = List(
        AddrWithBalance(transferReceiver1.toAddress, defaultFees.chainContract.withdrawFee),
        AddrWithBalance(transferReceiver2.toAddress, defaultFees.chainContract.withdrawFee)
      )
    )

    withExtensionDomain(settings) { d =>
      step(s"Start new epoch for ecBlock")
      d.advanceNewBlocks(reliable.address)

      val ecBlock = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(ecBlockLogs)
      def tryWithdraw(): Either[Throwable, BlockId] = d.appendMicroBlockE(
        d.chainContract.withdraw(transferReceiver1, ecBlock, transfer1Proofs, 0, transfer1.amount),
        d.chainContract.withdraw(transferReceiver2, ecBlock, transfer2Proofs, 1, transfer2.amount)
      )

      tryWithdraw() should produce("not found for the contract address")

      step("Append a CL micro block with ecBlock confirmation")
      d.ecClients.addKnown(ecBlock)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(reliable.account, ecBlock, e2CTransfersRootHashHex))
      d.advanceConsensusLayerChanged()

      tryWithdraw() should beRight
      withClue("Tokens came:") {
        val balance1After = d.balance(transferReceiver1.toAddress, d.token)
        balance1After shouldBe transfer1.amount

        val balance2After = d.balance(transferReceiver2.toAddress, d.token)
        balance2After shouldBe transfer2.amount
      }
    }
  }

  "Can't get transferred tokens with index that is out of bounds" in forAll(
    Table(
      "index" -> "errorMessage",
      -1      -> "Transfer index in block should be nonnegative, got -1",
      1024    -> "out of range allowed by proof list length",
      1       -> "Expected root hash"
    )
  ) { case (index, errorMessage) =>
    withExtensionDomain() { d =>
      step(s"Start new epoch with ecBlock")
      d.advanceNewBlocks(reliable.address)
      val ecBlock = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(ecBlockLogs)
      d.ecClients.addKnown(ecBlock)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(reliable.account, ecBlock, e2CTransfersRootHashHex))
      d.advanceConsensusLayerChanged()

      def tryWithdraw(): Either[Throwable, BlockId] =
        d.appendMicroBlockE(d.chainContract.withdraw(transferReceiver, ecBlock, transferProofs, index, transfer.amount))

      tryWithdraw() should produce(errorMessage)
    }
  }

  "Deny withdrawals with a non-positive amount" in forAll(
    Table(
      "index",
      0L,
      Long.MinValue
    )
  ) { amount =>
    withExtensionDomain() { d =>
      step(s"Start new epoch with ecBlock")
      d.advanceNewBlocks(reliable.address)
      val ecBlock = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(ecBlockLogs)
      d.ecClients.addKnown(ecBlock)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(reliable.account, ecBlock, e2CTransfersRootHashHex))
      d.advanceConsensusLayerChanged()

      def tryWithdraw(): Either[Throwable, BlockId] =
        d.appendMicroBlockE(d.chainContract.withdraw(transferReceiver, ecBlock, transferProofs, 0, amount))

      tryWithdraw() should produce("Amount should be positive")
    }
  }

  "Can't get transferred tokens if the data is incorrect and able if it is correct" in withExtensionDomain() { d =>
    step(s"Start new epoch with ecBlock")
    d.advanceNewBlocks(reliable.address)
    val ecBlock = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(ecBlockLogs)
    def tryWithdraw(): Either[Throwable, BlockId] =
      d.appendMicroBlockE(d.chainContract.withdraw(transferReceiver, ecBlock, transferProofs, 0, transfer.amount))

    tryWithdraw() should produce("not found for the contract address")
    d.ecClients.addKnown(ecBlock)
    d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(reliable.account, ecBlock, e2CTransfersRootHashHex))
    d.advanceConsensusLayerChanged()

    tryWithdraw() should beRight
    withClue("Tokens came:") {
      val balanceAfter = d.balance(transferReceiver.toAddress, d.token)
      balanceAfter shouldBe transfer.amount
    }
  }

  "L2-273 Can't get transferred tokens twice" in {
    val settings = defaultSettings.copy(
      additionalBalances = List(AddrWithBalance(transferReceiver.toAddress, defaultFees.chainContract.withdrawFee * 2))
    )

    withExtensionDomain(settings) { d =>
      step(s"Start new epoch with ecBlock")
      d.advanceNewBlocks(reliable.address)
      val ecBlock = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(ecBlockLogs)
      def tryWithdraw(): Either[Throwable, BlockId] =
        d.appendMicroBlockE(d.chainContract.withdraw(transferReceiver, ecBlock, transferProofs, 0, transfer.amount))

      tryWithdraw() should produce("not found for the contract address")
      d.ecClients.addKnown(ecBlock)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(reliable.account, ecBlock, e2CTransfersRootHashHex))
      d.advanceConsensusLayerChanged()

      tryWithdraw() should beRight
      tryWithdraw() should produce("Transfer #0 has been already taken")
    }
  }

  "Ignores wrong events" in {
    val wrongEventDef: Event = new Event(
      "SentSomething",
      List[TypeReference[?]](new TypeReference[Bytes20](false) {}).asJava
    )

    val wrongEventDefTopic = org.web3j.abi.EventEncoder.encode(wrongEventDef)
    val settings           = defaultSettings.withEnabledElMining

    forAll(
      Table(
        "transferEvent",
        transferEvent.copy(address = EthAddress.unsafeFrom("0xfe3b557e8fb62b89f4916b721be55ceb828dbd73")),
        transferEvent.copy(topics = List(wrongEventDefTopic))
      )
    ) { transferEvent =>
      withExtensionDomain(settings) { d =>
        step(s"Start new epoch with ecBlock1")
        d.advanceNewBlocks(reliable.address)
        val ecBlock1       = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(List(transferEvent))
        def tryWithdraw(): Either[Throwable, BlockId] =
          d.appendMicroBlockE(d.chainContract.withdraw(transferReceiver, ecBlock1, transferProofs, 0, transfer.amount))

        d.ecClients.willForge(ecBlock1)
        d.advanceConsensusLayerChanged()

        d.advanceMining()
        withClue("Transaction added to UTX pool: ") {
          val (txsOpt, _, _) = d.utxPool.packUnconfirmed(MultiDimensionalMiningConstraint.Unlimited, None)
          d.appendMicroBlockAndVerify(txsOpt.value*)
        }

        withClue("Can't withdraw: ") {
          tryWithdraw() should produce("Expected root hash: ")
        }
      }
    }
  }

  "L2-387 Fails on wrong data" in {
    val settings = defaultSettings.withEnabledElMining
    withExtensionDomain(settings) { d =>
      step(s"Start new epoch with ecBlock1")
      d.advanceNewBlocks(reliable.address)
      val ecBlock1       = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(List(transferEvent.copy(data = "d3ad884fa04292")))
      d.ecClients.willForge(ecBlock1)
      d.ecClients.willForge(d.createEcBlockBuilder("0-0", reliable).build())

      d.advanceConsensusLayerChanged()

      val (txsOpt, _, _) = d.utxPool.packUnconfirmed(MultiDimensionalMiningConstraint.Unlimited, None)
      txsOpt shouldBe empty
    }
  }

  "L2-308 Can't get transferred tokens from a fork and can after the fork becomes a main chain" in {
    val settings = defaultSettings.copy(initialMiners = List(reliable, malfunction)).withEnabledElMining
    withExtensionDomain(settings) { d =>
      step(s"Start a new epoch of malfunction miner ${malfunction.address} with ecBlock1")
      d.advanceNewBlocks(malfunction.address)
      // Need this block, because we can not rollback to the genesis block
      val ecBlock1 = d.createEcBlockBuilder("0", malfunction).buildAndSetLogs()
      d.advanceConsensusLayerChanged()

      d.ecClients.addKnown(ecBlock1)
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(malfunction.account, ecBlock1))
      d.advanceConsensusLayerChanged()

      step(s"Try to append a block with a wrong transfers root hash")
      d.advanceNewBlocks(malfunction.address)
      val ecBadBlock2 = d.createEcBlockBuilder("0-0", malfunction, ecBlock1).rewardPrevMiner().buildAndSetLogs(ecBlockLogs)
      d.advanceConsensusLayerChanged()

      // No root hash in extendMainChain tx
      d.appendMicroBlockAndVerify(d.chainContract.extendMainChain(malfunction.account, ecBadBlock2)) // No root hash
      d.receiveNetworkBlock(ecBadBlock2, malfunction.account)
      d.advanceConsensusLayerChanged()

      d.waitForCS[WaitForNewChain]("State is expected") { s =>
        s.chainSwitchInfo.referenceBlock.hash shouldBe ecBlock1.hash
      }

      step(s"Start an alternative chain by a reliable miner ${reliable.address} with ecBlock2")
      d.advanceNewBlocks(reliable.address)
      val ecBlock2 = d.createEcBlockBuilder("0-1", reliable, ecBlock1).rewardPrevMiner().buildAndSetLogs(ecBlockLogs)
      d.ecClients.willForge(ecBlock2)
      // Prepare a following block, because we start mining it immediately
      d.ecClients.willForge(d.createEcBlockBuilder("0-1-1", reliable, ecBlock2).build())

      d.advanceConsensusLayerChanged()
      d.waitForCS[Mining]("State is expected") { s =>
        s.nodeChainInfo.left.value.referenceBlock.hash shouldBe ecBlock1.hash
      }

      d.advanceMining()
      d.waitForCS[Mining]("State is expected") { s =>
        s.nodeChainInfo.left.value.referenceBlock.hash shouldBe ecBlock1.hash
      }

      step(s"Confirm startAltChain and append with new blocks and remove a malfunction miner")
      d.appendMicroBlockAndVerify(
        d.chainContract.startAltChain(reliable.account, ecBlock2, e2CTransfersRootHashHex),
        d.chainContract.leave(malfunction.account)
      )
      d.advanceConsensusLayerChanged()

      d.waitForCS[Mining]("State is expected") { _ => }

      def tryWithdraw(): Either[Throwable, BlockId] =
        d.appendMicroBlockE(d.chainContract.withdraw(transferReceiver, ecBlock2, transferProofs, 0, transfer.amount))
      withClue("Can't withdraw from a fork:") {
        tryWithdraw() should produce("is not finalized")
      }

      step(s"Moving whole network to the alternative chain with ecBlock3")
      d.advanceNewBlocks(reliable.address)
      val ecBlock3 = d.createEcBlockBuilder("0-1-1-1", reliable, ecBlock2).rewardPrevMiner(1).buildAndSetLogs()
      d.ecClients.willForge(ecBlock3)
      d.advanceConsensusLayerChanged()

      step("Confirm extendAltChain to make this chain main")
      d.advanceMining()

      d.appendMicroBlockAndVerify(d.chainContract.extendAltChain(reliable.account, ecBlock3, chainId = 1))
      d.advanceConsensusLayerChanged()

      d.waitForCS[Mining]("State is expected") { _ => }

      withClue("Can withdraw from the new main chain:") {
        tryWithdraw() should beRight
      }

      withClue("Tokens came:") {
        val balanceAfter = d.balance(transferReceiver.toAddress, d.token)
        balanceAfter shouldBe transfer.amount
      }
    }
  }
}
