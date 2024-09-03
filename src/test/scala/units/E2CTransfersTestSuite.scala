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
import units.ELUpdater.State.Working
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
    additionalBalances = List(AddrWithBalance(transferReceiver.toAddress, chainContract.withdrawFee))
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
        AddrWithBalance(transferReceiver1.toAddress, chainContract.withdrawFee),
        AddrWithBalance(transferReceiver2.toAddress, chainContract.withdrawFee)
      )
    )

    withExtensionDomain(settings) { d =>
      val ecBlock = d.createNextEcBlock(
        hash = d.createBlockHash("0"),
        parent = d.ecGenesisBlock,
        minerRewardL2Address = reliable.elRewardAddress
      )

      def tryWithdraw(): Either[Throwable, BlockId] = d.appendMicroBlockE(
        chainContract.withdraw(transferReceiver1, ecBlock, transfer1Proofs, 0, transfer1.amount),
        chainContract.withdraw(transferReceiver2, ecBlock, transfer2Proofs, 1, transfer2.amount)
      )

      step(s"Start new epoch for ecBlock ${ecBlock.hash}")
      d.advanceNewBlocks(reliable.address)
      tryWithdraw() should produce("not found for the contract address")

      step("Append a CL micro block with ecBlock confirmation")
      d.ecClients.setBlockLogs(ecBlock.hash, Bridge.ElSentNativeEventTopic, ecBlockLogs)
      d.ecClients.addKnown(ecBlock)
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(reliable.account, ecBlock, d.blockchain.height, e2CTransfersRootHashHex))
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
      val ecBlock = d.createNextEcBlock(
        hash = d.createBlockHash("0"),
        parent = d.ecGenesisBlock,
        minerRewardL2Address = reliable.elRewardAddress
      )

      step(s"Start new epoch with ecBlock ${ecBlock.hash}")
      d.advanceNewBlocks(reliable.address)
      d.ecClients.setBlockLogs(ecBlock.hash, Bridge.ElSentNativeEventTopic, ecBlockLogs) // TODO move up
      d.ecClients.addKnown(ecBlock)
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(reliable.account, ecBlock, d.blockchain.height, e2CTransfersRootHashHex))
      d.advanceConsensusLayerChanged()

      def tryWithdraw(): Either[Throwable, BlockId] =
        d.appendMicroBlockE(chainContract.withdraw(transferReceiver, ecBlock, transferProofs, index, transfer.amount))

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
      val ecBlock = d.createNextEcBlock(
        hash = d.createBlockHash("0"),
        parent = d.ecGenesisBlock,
        minerRewardL2Address = reliable.elRewardAddress
      )

      step(s"Start new epoch with ecBlock ${ecBlock.hash}")
      d.advanceNewBlocks(reliable.address)
      d.ecClients.setBlockLogs(ecBlock.hash, Bridge.ElSentNativeEventTopic, ecBlockLogs) // TODO move up
      d.ecClients.addKnown(ecBlock)
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(reliable.account, ecBlock, d.blockchain.height, e2CTransfersRootHashHex))
      d.advanceConsensusLayerChanged()

      def tryWithdraw(): Either[Throwable, BlockId] =
        d.appendMicroBlockE(chainContract.withdraw(transferReceiver, ecBlock, transferProofs, 0, amount))

      tryWithdraw() should produce("Amount should be positive")
    }
  }

  "Can't get transferred tokens if the data is incorrect and able if it is correct" in withExtensionDomain() { d =>
    val ecBlock = d.createNextEcBlock(
      hash = d.createBlockHash("0"),
      parent = d.ecGenesisBlock,
      minerRewardL2Address = reliable.elRewardAddress
    )

    def tryWithdraw(): Either[Throwable, BlockId] =
      d.appendMicroBlockE(chainContract.withdraw(transferReceiver, ecBlock, transferProofs, 0, transfer.amount))

    step(s"Start new epoch with ecBlock ${ecBlock.hash}")
    d.advanceNewBlocks(reliable.address)
    tryWithdraw() should produce("not found for the contract address")
    d.ecClients.setBlockLogs(ecBlock.hash, Bridge.ElSentNativeEventTopic, ecBlockLogs) // TODO move up
    d.ecClients.addKnown(ecBlock)
    d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(reliable.account, ecBlock, d.blockchain.height, e2CTransfersRootHashHex))
    d.advanceConsensusLayerChanged()

    tryWithdraw() should beRight
    withClue("Tokens came:") {
      val balanceAfter = d.balance(transferReceiver.toAddress, d.token)
      balanceAfter shouldBe transfer.amount
    }
  }

  "Can't get transferred tokens twice" in {
    val settings = defaultSettings.copy(
      additionalBalances = List(AddrWithBalance(transferReceiver.toAddress, chainContract.withdrawFee * 2))
    )

    withExtensionDomain(settings) { d =>
      val ecBlock = d.createNextEcBlock(
        hash = d.createBlockHash("0"),
        parent = d.ecGenesisBlock,
        minerRewardL2Address = reliable.elRewardAddress
      )

      def tryWithdraw(): Either[Throwable, BlockId] =
        d.appendMicroBlockE(chainContract.withdraw(transferReceiver, ecBlock, transferProofs, 0, transfer.amount))

      step(s"Start new epoch with ecBlock ${ecBlock.hash}")
      d.advanceNewBlocks(reliable.address)
      tryWithdraw() should produce("not found for the contract address")
      d.ecClients.setBlockLogs(ecBlock.hash, Bridge.ElSentNativeEventTopic, ecBlockLogs) // TODO move up
      d.ecClients.addKnown(ecBlock)
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(reliable.account, ecBlock, d.blockchain.height, e2CTransfersRootHashHex))
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
        val ecBlock1 = d.createNextEcBlock(
          hash = d.createBlockHash("0"),
          parent = d.ecGenesisBlock,
          minerRewardL2Address = reliable.elRewardAddress
        )

        def tryWithdraw(): Either[Throwable, BlockId] =
          d.appendMicroBlockE(chainContract.withdraw(transferReceiver, ecBlock1, transferProofs, 0, transfer.amount))

        step(s"Start new epoch with ecBlock1 ${ecBlock1.hash}")
        d.advanceNewBlocks(reliable.address)
        val transferEvents = List(transferEvent)
        d.ecClients.setBlockLogs(ecBlock1.hash, Bridge.ElSentNativeEventTopic, transferEvents) // TODO move up
        d.ecClients.willForge(ecBlock1)
        d.advanceConsensusLayerChanged()

        d.advanceBlockDelay()
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

  "Fails on wrong data" in {
    val settings = defaultSettings.withEnabledElMining
    withExtensionDomain(settings) { d =>
      val ecBlock1 = d.createNextEcBlock(
        hash = d.createBlockHash("0"),
        parent = d.ecGenesisBlock,
        minerRewardL2Address = reliable.elRewardAddress
      )

      val ecBlock2 = d.createNextEcBlock(
        hash = d.createBlockHash("0-0"),
        parent = ecBlock1,
        minerRewardL2Address = reliable.elRewardAddress
      )

      step(s"Start new epoch with ecBlock1 ${ecBlock1.hash}")
      d.advanceNewBlocks(reliable.address)
      val transferEvents = List(transferEvent.copy(data = "d3ad884fa04292"))
      d.ecClients.setBlockLogs(ecBlock1.hash, Bridge.ElSentNativeEventTopic, transferEvents) // TODO move up
      d.ecClients.willForge(ecBlock1)
      d.ecClients.willForge(ecBlock2)

      d.advanceConsensusLayerChanged()

      val (txsOpt, _, _) = d.utxPool.packUnconfirmed(MultiDimensionalMiningConstraint.Unlimited, None)
      txsOpt shouldBe empty
    }
  }

  "Can't get transferred tokens from a fork and can after the fork becomes a main chain" in {
    val settings = defaultSettings.copy(initialMiners = List(reliable, malfunction)).withEnabledElMining
    withExtensionDomain(settings) { d =>
      val ecBlock1Hash        = d.createBlockHash("0")
      val ecBlock2BadHash     = d.createBlockHash("0-0")
      val ecBlock2Hash        = d.createBlockHash("0-1")
      val ecBlock3IgnoredHash = d.createBlockHash("0-1-1")
      val ecBlock3Hash        = d.createBlockHash("0-1-1-1")

      // mm - malfunction miner, rm - reliable
      log.debug(
        s"Final chain: $ecBlock3Hash (3, rm) -> $ecBlock2Hash (2, rm) -> $ecBlock1Hash (1, mm) -> ${d.ecGenesisBlock.hash} (genesis)"
      )
      log.debug(s"Invalid chain: $ecBlock2BadHash (bad, mm) -> $ecBlock1Hash (1, mm) -> ${d.ecGenesisBlock.hash} (genesis)")
      log.debug(s"Ignored blocks: $ecBlock3IgnoredHash (ignored) -> $ecBlock2Hash")

      step(s"Start a new epoch of malfunction miner ${malfunction.address} with ecBlock1 $ecBlock1Hash")
      d.advanceNewBlocks(malfunction.address)
      d.advanceConsensusLayerChanged()

      val ecBlock1 = d.createNextEcBlock( // Need this block, because we can not rollback to the genesis block
        hash = ecBlock1Hash,
        parent = d.ecGenesisBlock,
        minerRewardL2Address = malfunction.elRewardAddress
      )
      d.ecClients.setBlockLogs(ecBlock1Hash, Bridge.ElSentNativeEventTopic, Nil)

      d.ecClients.addKnown(ecBlock1)
      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(malfunction.account, ecBlock1, d.blockchain.height))
      d.advanceConsensusLayerChanged()

      step(s"Try to append a block with a wrong transfers root hash $ecBlock2BadHash")
      d.advanceNewBlocks(malfunction.address)
      d.advanceBlockDelay()

      val ecBlock2Bad = d.createNextEcBlock( // No root hash in extendMainChain tx
        hash = ecBlock2BadHash,
        parent = ecBlock1,
        minerRewardL2Address = malfunction.elRewardAddress,
        withdrawals = Vector(d.createWithdrawal(0, malfunction.elRewardAddress))
      )
      d.ecClients.setBlockLogs(ecBlock2BadHash, Bridge.ElSentNativeEventTopic, ecBlockLogs)

      d.appendMicroBlockAndVerify(chainContract.extendMainChainV3(malfunction.account, ecBlock2Bad, d.blockchain.height)) // No root hash
      d.receiveNetworkBlock(ecBlock2Bad, malfunction.account)
      d.advanceConsensusLayerChanged()

      // TODO
      withClue("State is expected:") {
        val s  = is[Working[?]](d.consensusClient.elu.state)
        val cs = is[WaitForNewChain](s.chainStatus)
        cs.chainSwitchInfo.referenceBlock.hash shouldBe ecBlock1Hash
      }

      step(s"Start an alternative chain by a reliable miner ${reliable.address} with ecBlock2 $ecBlock2Hash")
      d.advanceNewBlocks(reliable.address)

      val ecBlock2 = d.createNextEcBlock(
        hash = ecBlock2Hash,
        parent = ecBlock1,
        minerRewardL2Address = reliable.elRewardAddress,
        withdrawals = Vector(d.createWithdrawal(0, malfunction.elRewardAddress))
      )
      d.ecClients.setBlockLogs(ecBlock2.hash, Bridge.ElSentNativeEventTopic, ecBlockLogs)

      val ecBlock3Ignored = d.createNextEcBlock(
        hash = ecBlock3IgnoredHash,
        parent = ecBlock2,
        minerRewardL2Address = reliable.elRewardAddress
      )

      d.ecClients.willForge(ecBlock2)
      d.ecClients.willForge(ecBlock3Ignored) // Prepare a following block, because we start mining it immediately
      d.advanceConsensusLayerChanged()

      // TODO
      withClue("State is expected:") {
        val s  = is[Working[?]](d.consensusClient.elu.state)
        val cs = is[Mining](s.chainStatus)
        cs.nodeChainInfo.left.value.referenceBlock.hash shouldBe ecBlock1Hash
      }

      d.advanceBlockDelay() // Forge ecBlock2

      step(s"Confirm startAltChain and append with new blocks and remove a malfunction miner")
      d.appendMicroBlockAndVerify(
        chainContract.startAltChainV3(reliable.account, ecBlock2, d.blockchain.height, e2CTransfersRootHashHex), // TODO check utx?
        chainContract.leave(malfunction.account)
      )
      d.advanceConsensusLayerChanged()

      // TODO
      withClue("State is expected:") {
        val s = is[Working[?]](d.consensusClient.elu.state)
        is[Mining](s.chainStatus)
      }

      def tryWithdraw(): Either[Throwable, BlockId] =
        d.appendMicroBlockE(chainContract.withdraw(transferReceiver, ecBlock2, transferProofs, 0, transfer.amount))
      withClue("Can't withdraw from a fork:") {
        tryWithdraw() should produce("is not finalized")
      }

      step(s"Moving whole network to the alternative chain with ecBlock3 $ecBlock3Hash")
      d.advanceNewBlocks(reliable.address)

      // TODO a merged function
      val ecBlock3 = d.createNextEcBlock(
        hash = ecBlock3Hash,
        parent = ecBlock2,
        minerRewardL2Address = reliable.elRewardAddress,
        withdrawals = Vector(d.createWithdrawal(1, reliable.elRewardAddress))
      )
      d.ecClients.setBlockLogs(ecBlock3.hash, Bridge.ElSentNativeEventTopic, Nil)
      d.ecClients.willForge(ecBlock3)
      d.advanceConsensusLayerChanged() // Forge ecBlock3

      step("Confirm extendAltChain to make this chain main")
      d.advanceBlockDelay()
      d.appendMicroBlockAndVerify(chainContract.extendAltChainV3(reliable.account, 1, ecBlock3, d.blockchain.height))
      d.advanceConsensusLayerChanged()

      // TODO
      withClue("State is expected:") {
        val s = is[Working[?]](d.consensusClient.elu.state)
        is[Mining](s.chainStatus)
      }

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
