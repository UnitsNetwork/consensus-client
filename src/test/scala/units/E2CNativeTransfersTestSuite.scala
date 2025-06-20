package units

import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.mining.MultiDimensionalMiningConstraint
import com.wavesplatform.test.produce
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet
import units.ELUpdater.State.ChainStatus.{Mining, WaitForNewChain}
import units.client.contract.HasConsensusLayerDappTxHelpers.DefaultFees
import units.el.NativeBridge
import units.util.HexBytesConverter

class E2CNativeTransfersTestSuite extends BaseTestSuite {
  private val transferReceiver     = TxHelpers.secondSigner
  private val transfer             = NativeBridge.ElSentNativeEvent(transferReceiver.toAddress, 1)
  private val transferEvent        = getLogsResponseEntry(transfer)
  private val ecBlockLogs          = List(transferEvent)
  private val transfersRootHashHex = HexBytesConverter.toHex(NativeBridge.mkTransfersHash(ecBlockLogs).explicitGet())
  private val transferProofs       = NativeBridge.mkTransferProofs(List(transfer), 0).reverse // Contract requires from bottom to top

  private val reliable    = ElMinerSettings(Wallet.generateNewAccount(super.defaultSettings.walletSeed, 0))
  private val malfunction = ElMinerSettings(TxHelpers.signer(2)) // Prevents block finalization

  override protected val defaultSettings: TestSettings = super.defaultSettings.copy(
    initialMiners = List(reliable),
    additionalBalances = List(AddrWithBalance(transferReceiver.toAddress, DefaultFees.ChainContract.withdrawFee))
  )

  "Multiple withdrawals" in {
    val transferReceiver1    = transferReceiver
    val transferReceiver2    = TxHelpers.signer(2)
    val transfer1            = NativeBridge.ElSentNativeEvent(transferReceiver1.toAddress, 1)
    val transfer2            = NativeBridge.ElSentNativeEvent(transferReceiver2.toAddress, 1)
    val transferEvents       = List(transfer1, transfer2)
    val ecBlockLogs          = transferEvents.map(getLogsResponseEntry)
    val transfersRootHashHex = HexBytesConverter.toHex(NativeBridge.mkTransfersHash(ecBlockLogs).explicitGet())
    val transfer1Proofs      = NativeBridge.mkTransferProofs(transferEvents, 0).reverse
    val transfer2Proofs      = NativeBridge.mkTransferProofs(transferEvents, 1).reverse

    val settings = defaultSettings.copy(
      additionalBalances = List(
        AddrWithBalance(transferReceiver1.toAddress, DefaultFees.ChainContract.withdrawFee),
        AddrWithBalance(transferReceiver2.toAddress, DefaultFees.ChainContract.withdrawFee)
      )
    )

    withExtensionDomain(settings) { d =>
      step(s"Start new epoch for ecBlock")
      d.advanceNewBlocks(reliable.address)

      val ecBlock = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(ecBlockLogs)
      def tryWithdraw(): Either[Throwable, BlockId] = d.appendMicroBlockE(
        d.ChainContract.withdraw(transferReceiver1, ecBlock, transfer1Proofs, 0, transfer1.amount),
        d.ChainContract.withdraw(transferReceiver2, ecBlock, transfer2Proofs, 1, transfer2.amount)
      )

      tryWithdraw() should produce("not found for the contract address")

      step("Append a CL micro block with ecBlock confirmation")
      d.ecClients.addKnown(ecBlock)
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(reliable.account, ecBlock, transfersRootHashHex))
      d.advanceConsensusLayerChanged()

      tryWithdraw() should beRight
      withClue("Tokens came:") {
        val balance1After = d.balance(transferReceiver1.toAddress, d.nativeTokenId)
        balance1After shouldBe transfer1.amount

        val balance2After = d.balance(transferReceiver2.toAddress, d.nativeTokenId)
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
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(reliable.account, ecBlock, transfersRootHashHex))
      d.advanceConsensusLayerChanged()

      def tryWithdraw(): Either[Throwable, BlockId] =
        d.appendMicroBlockE(d.ChainContract.withdraw(transferReceiver, ecBlock, transferProofs, index, transfer.amount))

      tryWithdraw() should produce(errorMessage)
    }
  }

  private def wrongAmountTest(amount: Long): Unit = withExtensionDomain() { d =>
    step("Start new epoch with ecBlock")
    d.advanceNewBlocks(reliable.address)
    val ecBlock = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(ecBlockLogs)
    d.ecClients.addKnown(ecBlock)
    d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(reliable.account, ecBlock, transfersRootHashHex))
    d.advanceConsensusLayerChanged()

    def tryWithdraw(): Either[Throwable, BlockId] =
      d.appendMicroBlockE(d.ChainContract.withdraw(transferReceiver, ecBlock, transferProofs, 0, amount))

    tryWithdraw() should produce("Amount should be positive")
  }

  "L2-360 Deny negative amount" in wrongAmountTest(Long.MinValue)

  "Deny withdrawals with invalid amount" in forAll(Table("index", 0L, transfer.amount - 1))(wrongAmountTest)

  "Can't get transferred tokens if the data is incorrect and able if it is correct" in withExtensionDomain() { d =>
    step(s"Start new epoch with ecBlock")
    d.advanceNewBlocks(reliable.address)
    val ecBlock = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(ecBlockLogs)
    def tryWithdraw(): Either[Throwable, BlockId] =
      d.appendMicroBlockE(d.ChainContract.withdraw(transferReceiver, ecBlock, transferProofs, 0, transfer.amount))

    tryWithdraw() should produce("not found for the contract address")
    d.ecClients.addKnown(ecBlock)
    d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(reliable.account, ecBlock, transfersRootHashHex))
    d.advanceConsensusLayerChanged()

    tryWithdraw() should beRight
    withClue("Tokens came:") {
      val balanceAfter = d.balance(transferReceiver.toAddress, d.nativeTokenId)
      balanceAfter shouldBe transfer.amount
    }
  }

  "L2-273 Can't get transferred tokens twice" in {
    val settings = defaultSettings.copy(
      additionalBalances = List(AddrWithBalance(transferReceiver.toAddress, DefaultFees.ChainContract.withdrawFee * 2))
    )

    withExtensionDomain(settings) { d =>
      step(s"Start new epoch with ecBlock")
      d.advanceNewBlocks(reliable.address)
      val ecBlock = d.createEcBlockBuilder("0", reliable).buildAndSetLogs(ecBlockLogs)
      def tryWithdraw(): Either[Throwable, BlockId] =
        d.appendMicroBlockE(d.ChainContract.withdraw(transferReceiver, ecBlock, transferProofs, 0, transfer.amount))

      tryWithdraw() should produce("not found for the contract address")
      d.ecClients.addKnown(ecBlock)
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(reliable.account, ecBlock, transfersRootHashHex))
      d.advanceConsensusLayerChanged()

      tryWithdraw() should beRight
      tryWithdraw() should produce("Transfer #0 has been already taken")
    }
  }

  "Fails on wrong data" in {
    val settings    = defaultSettings.withEnabledElMining
    val malfunction = reliable // Reliable is a default miner, but here it is malfunction
    withExtensionDomain(settings) { d =>
      step(s"Start new epoch with ecBlock1")
      d.advanceNewBlocks(malfunction.address)
      val ecBlock1 = d.createEcBlockBuilder("0", malfunction).buildAndSetLogs(List(transferEvent.copy(data = "d3ad884fa04292")))
      d.ecClients.willForge(ecBlock1)
      d.ecClients.willForge(d.createEcBlockBuilder("0-0", malfunction, ecBlock1).build())
      d.advanceConsensusLayerChanged()
      d.advanceMining()

      val (txsOpt, _, _) = d.utxPool.packUnconfirmed(MultiDimensionalMiningConstraint.Unlimited, None)
      txsOpt shouldBe empty
    }
  }

  "Can't get transferred tokens from a fork and can after the fork becomes a main chain" in {
    val settings = defaultSettings.copy(initialMiners = List(reliable, malfunction)).withEnabledElMining
    withExtensionDomain(settings) { d =>
      step(s"Start a new epoch of malfunction miner ${malfunction.address} with ecBlock1")
      d.advanceNewBlocks(malfunction.address)
      // Need this block, because we can not rollback to the genesis block
      val ecBlock1 = d.createEcBlockBuilder("0", malfunction).buildAndSetLogs()
      d.advanceConsensusLayerChanged()

      d.ecClients.addKnown(ecBlock1)
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(malfunction.account, ecBlock1))
      d.advanceConsensusLayerChanged()

      step(s"Try to append a block with a wrong transfers root hash")
      d.advanceNewBlocks(malfunction.address)
      val ecBadBlock2 = d.createEcBlockBuilder("0-e", malfunction, ecBlock1).rewardPrevMiner().buildAndSetLogs(ecBlockLogs)
      d.advanceConsensusLayerChanged()

      // No root hash in extendMainChain tx
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(malfunction.account, ecBadBlock2))
      d.receiveNetworkBlock(ecBadBlock2, malfunction.account)
      d.advanceConsensusLayerChanged()

      d.waitForCS[WaitForNewChain]("State is expected") { s =>
        s.chainSwitchInfo.referenceBlock.hash shouldBe ecBlock1.hash
      }

      step(s"Start an alternative chain by a reliable miner ${reliable.address} with ecBlock2")
      d.advanceNewBlocks(reliable.address)
      val ecBlock2 = d.createEcBlockBuilder("0-1", reliable, ecBlock1).rewardPrevMiner().buildAndSetLogs(ecBlockLogs)
      d.ecClients.willSimulate(ecBlock2)
      // Prepare a following block, because we start mining it immediately
      d.ecClients.willForge(d.createEcBlockBuilder("0-1-i", reliable, ecBlock2).build())

      d.advanceConsensusLayerChanged()
      d.waitForCS[Mining]("State is expected") { s =>
        s.nodeChainInfo.left.value.referenceBlock.hash shouldBe ecBlock1.hash
      }

      step(s"Confirm startAltChain and append with new blocks and remove a malfunction miner")
      d.appendMicroBlockAndVerify(
        d.ChainContract.startAltChain(reliable.account, ecBlock2, transfersRootHashHex),
        d.ChainContract.leave(malfunction.account)
      )
      d.advanceConsensusLayerChanged()

      d.waitForCS[Mining]("State is expected") { _ => }

      def tryWithdraw(): Either[Throwable, BlockId] =
        d.appendMicroBlockE(d.ChainContract.withdraw(transferReceiver, ecBlock2, transferProofs, 0, transfer.amount))
      withClue("Can't withdraw from a fork:") {
        tryWithdraw() should produce("is not finalized")
      }

      step(s"Moving whole network to the alternative chain with ecBlock3")
      d.advanceNewBlocks(reliable.address)
      val ecBlock3 = d.createEcBlockBuilder("0-1-1", reliable, ecBlock2).rewardPrevMiner(1).buildAndSetLogs()
      d.ecClients.willSimulate(ecBlock3)
      d.ecClients.willForge(d.createEcBlockBuilder("0-1-1-i", reliable, ecBlock3).build())
      d.advanceConsensusLayerChanged()

      step("Confirm extendAltChain to make this chain main")
      d.advanceMining()

      d.appendMicroBlockAndVerify(d.ChainContract.extendAltChain(reliable.account, ecBlock3, chainId = 1))
      d.advanceConsensusLayerChanged()

      d.waitForCS[Mining]("State is expected") { _ => }

      withClue("Can withdraw from the new main chain:") {
        tryWithdraw() should beRight
      }

      withClue("Tokens came:") {
        val balanceAfter = d.balance(transferReceiver.toAddress, d.nativeTokenId)
        balanceAfter shouldBe transfer.amount
      }
    }
  }
}
