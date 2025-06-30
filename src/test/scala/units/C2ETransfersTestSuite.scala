package units

import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.state.IntegerDataEntry
import com.wavesplatform.test.produce
import com.wavesplatform.transaction.utils.EthConverters.EthereumAddressExt
import com.wavesplatform.transaction.{Asset, TxHelpers}
import com.wavesplatform.wallet.Wallet
import org.web3j.abi.EventEncoder
import units.client.engine.model.{EcBlock, GetLogsResponseEntry, Withdrawal}
import units.el.{NativeBridge, StandardBridge}
import units.eth.{EthAddress, EthNumber}

class C2ETransfersTestSuite extends BaseTestSuite {
  private val transferSenderAccount  = TxHelpers.secondSigner
  private val validTransferRecipient = "1111111111111111111111111111111111111111"
  private val issueAssetTxn          = TxHelpers.issue(issuer = transferSenderAccount)
  private val issueAsset             = issueAssetTxn.asset

  override protected val defaultSettings: TestSettings = super.defaultSettings.copy(
    additionalBalances = List(AddrWithBalance(transferSenderAccount.toAddress)),
    registerWwavesToken = true
  )

  "Pass a valid address with a valid payment" in {
    transferFuncTest(validTransferRecipient) should beRight
  }

  "Deny an invalid address" in forAll(
    Table(
      "invalid address"                            -> "message",
      ""                                           -> "Invalid Ethereum address",
      "0x"                                         -> "Invalid Ethereum address",
      "000000000000000000000000000000000000000003" -> "Invalid Ethereum address",
      "0x00000000000000000000000000000000000001"   -> "Invalid Ethereum address",
      "QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ"   -> "Unrecognized character: q"
    )
  ) { case (address, message) =>
    transferFuncTest(address) should produce(message)
  }

  // TODO: Return the queue
  "Deny invalid payment amount for native transfers" ignore forAll(
    Table(
      ("invalid payment amount", "initial queue size", "message"),
      (1, 0, "should be >= 1000000 for queue size of 1"),
      (1_000_000, 1600, "should be >= 100000000 for queue size of 1601"),
      (1_000_000_001, 6401, "Transfers denied for queue size of 6402")
    )
  ) { case (transferAmount, initQueueSize, message) =>
    transferFuncTest(validTransferRecipient, transferAmount, queueSize = initQueueSize) should produce(message)
  }

  "Deny an unregistered asset transfers" in withExtensionDomain() { d =>
    d.appendMicroBlock(issueAssetTxn)
    val r = d.appendMicroBlockE(d.ChainContract.transferUnsafe(transferSenderAccount, validTransferRecipient, issueAsset, 1_000_000))
    r should produce(s"Can't find in the registry: ${issueAssetTxn.id()}")
  }

  "Can't register before activation" in withExtensionDomain(defaultSettings.copy(enableTokenTransfersEpoch = Int.MaxValue)) { d =>
    d.appendMicroBlock(issueAssetTxn)
    val r = d.appendMicroBlockE(d.ChainContract.registerAsset(issueAsset, mkRandomEthAddress(), 8))
    r should produce("Asset transfers must be activated")
  }

  "Can't transfer WAVES before activation" in withExtensionDomain(defaultSettings.copy(enableTokenTransfersEpoch = Int.MaxValue)) { d =>
    val r = d.appendMicroBlockE(d.ChainContract.transferUnsafe(transferSenderAccount, validTransferRecipient, Asset.Waves, 1_000_000))
    r should produce("Asset transfers must be activated")
  }

  "Allow a registered asset" in forAll(
    Table(
      "Asset",
      Asset.Waves,
      issueAsset
    )
  ) { asset =>
    transferFuncTest(validTransferRecipient, assetId = Some(asset), register = true) should beRight
  }

  "Register multiple assets" in withExtensionDomain() { d =>
    val issueAsset2Txn = TxHelpers.issue(issuer = transferSenderAccount)
    d.appendMicroBlock(issueAssetTxn, issueAsset2Txn)

    val assets = List(issueAsset, issueAsset2Txn.asset)
    val registerTxn = d.ChainContract.registerAssets(
      assets = assets,
      erc20AddressHex = List.fill(2)(mkRandomEthAddress().hex),
      elDecimals = List.fill(2)(8),
      invoker = d.chainContractAccount
    )

    d.appendMicroBlockE(registerTxn) should beRight

    val registeredAssets = d.chainContractClient.getAllRegisteredAssets.map(_.asset)
    registeredAssets shouldBe List(Asset.Waves, issueAsset, issueAsset2Txn.asset)
  }

  "Strict C2E transfers" - {
    val userAmount           = 1
    val nativeTokensClAmount = UnitsConvert.toUnitsInWaves(userAmount)
    val nativeTokensElAmount = UnitsConvert.toGwei(userAmount)
    val assetTokensClAmount  = UnitsConvert.toWavesAtomic(userAmount, WavesDecimals)
    val assetTokensElAmount  = UnitsConvert.toAtomic(userAmount, WavesDecimals)
    val destElAddress        = EthAddress.unsafeFrom(s"0x$validTransferRecipient")

    def mkNativeTransfer(d: ExtensionDomain, ts: Long) = d.ChainContract.transfer(
      sender = transferSenderAccount,
      destElAddress = destElAddress,
      asset = d.nativeTokenId,
      amount = nativeTokensClAmount,
      timestamp = ts
    )

    def mkAssetTransfer(d: ExtensionDomain, ts: Long) = d.ChainContract.transfer(
      sender = transferSenderAccount,
      destElAddress = destElAddress,
      asset = Asset.Waves,
      amount = assetTokensClAmount,
      timestamp = ts
    )

    val reliable = ElMinerSettings(Wallet.generateNewAccount(super.defaultSettings.walletSeed, 0))
    val second   = ElMinerSettings(TxHelpers.signer(2))
    val settings = defaultSettings.copy(initialMiners = List(reliable, second)).withEnabledElMining
    "Not enough" - {
      val malfunction = second

      "Native C2E transfers" in withExtensionDomain(settings) { d =>
        step("Activate strict C2E transfers")
        val now = System.currentTimeMillis()
        d.appendBlock(
          d.ChainContract.enableStrictTransfers(d.blockchain.height + 1),
          // Transfers of native token
          TxHelpers.reissue(d.nativeTokenId, d.chainContractAccount, nativeTokensClAmount * 2),
          TxHelpers.transfer(d.chainContractAccount, transferSenderAccount.toAddress, nativeTokensClAmount * 2, d.nativeTokenId),
          mkNativeTransfer(d, now),
          mkNativeTransfer(d, now + 1)
        )

        step(s"Start a new epoch of malfunction miner ${malfunction.address}")
        d.advanceNewBlocks(malfunction)
        d.advanceConsensusLayerChanged()

        val wrongBlock = d
          .createEcBlockBuilder("0", malfunction)
          .updateBlock { b =>
            b.copy(withdrawals = Vector(Withdrawal(0, destElAddress, nativeTokensElAmount))) // Only one
          }
          .buildAndSetLogs()

        d.appendMicroBlock(d.ChainContract.extendMainChain(malfunction.account, wrongBlock, lastC2ETransferIndex = 0))
        d.advanceConsensusLayerChanged()

        step(s"Receive wrongBlock ${wrongBlock.hash}")
        d.receiveNetworkBlock(wrongBlock, malfunction.account)
        withClue("Full EL block validation:") {
          d.triggerScheduledTasks()
          if (d.pollSentNetworkBlock().isDefined) fail(s"${wrongBlock.hash} should be ignored")
        }
      }

      "Asset C2E transfers" in withExtensionDomain(settings) { d =>
        val malfunction = second

        step("Activate strict C2E transfers")
        val now = System.currentTimeMillis()
        d.appendBlock(
          d.ChainContract.enableStrictTransfers(d.blockchain.height + 1),
          // Transfers of Waves
          mkAssetTransfer(d, now),
          mkAssetTransfer(d, now + 1)
        )

        val transferEvents = List(
          StandardBridge.ERC20BridgeFinalized( // Only one
            WWavesAddress,
            EthAddress.unsafeFrom(transferSenderAccount.toAddress.toEthAddress),
            destElAddress,
            EAmount(assetTokensElAmount.bigInteger)
          )
        )

        step(s"Start a new epoch of malfunction miner ${malfunction.address}")
        d.advanceNewBlocks(malfunction)
        d.advanceConsensusLayerChanged()

        val wrongBlock = d
          .createEcBlockBuilder("0", malfunction)
          .buildAndSetLogs(transferEvents.map(getLogsResponseEntry(_)))

        d.appendMicroBlock(d.ChainContract.extendMainChain(malfunction.account, wrongBlock, lastC2ETransferIndex = 0))
        d.advanceConsensusLayerChanged()

        step(s"Receive wrongBlock ${wrongBlock.hash}")
        d.receiveNetworkBlock(wrongBlock, malfunction.account)
        withClue("Full EL block validation:") {
          d.triggerScheduledTasks()
          if (d.pollSentNetworkBlock().isDefined) fail(s"${wrongBlock.hash} should be ignored")
        }
      }
    }

    "Enough C2E transfers" in withExtensionDomain(settings) { d =>
      step("Activate strict C2E transfers")
      val now = System.currentTimeMillis()
      d.appendBlock(
        d.ChainContract.enableStrictTransfers(d.blockchain.height + 1),
        // Transfers
        TxHelpers.reissue(d.nativeTokenId, d.chainContractAccount, nativeTokensClAmount * 3),
        TxHelpers.transfer(d.chainContractAccount, transferSenderAccount.toAddress, nativeTokensClAmount * 3, d.nativeTokenId),
        mkNativeTransfer(d, now),
        mkNativeTransfer(d, now + 1),
        mkAssetTransfer(d, now + 2),
        mkAssetTransfer(d, now + 3)
      )

      val transferLogEntries = (0 to 1).map { i =>
        val event = StandardBridge.ERC20BridgeFinalized(
          WWavesAddress,
          EthAddress.unsafeFrom(transferSenderAccount.toAddress.toEthAddress),
          destElAddress,
          EAmount(assetTokensElAmount.bigInteger)
        )
        getLogsResponseEntry(event, i)
      }.toList

      step(s"Start a new epoch of miner ${second.address}")
      d.advanceNewBlocks(second)
      d.advanceConsensusLayerChanged()

      val block = d
        .createEcBlockBuilder("0", second)
        .updateBlock { b =>
          b.copy(withdrawals = (0 to 1).map(Withdrawal(_, destElAddress, nativeTokensElAmount)).toVector)
        }
        .buildAndSetLogs(transferLogEntries)

      d.appendMicroBlock(
        // Transfers, those miner could not see during building a payload
        mkNativeTransfer(d, now + 4),
        mkAssetTransfer(d, now + 5),
        // EL-block confirmation
        d.ChainContract.extendMainChain(second.account, block, lastC2ETransferIndex = 3)
      )
      d.advanceConsensusLayerChanged()

      step(s"Receive block ${block.hash}")
      d.receiveNetworkBlock(block, second.account)
      withClue("Full EL block validation:") {
        d.triggerScheduledTasks()
        if (d.pollSentNetworkBlock().isEmpty) fail(s"${block.hash} should not be ignored")
      }
    }

    "More than maximum C2E native transfers" in withExtensionDomain(settings) { d =>
      step("Activate strict C2E transfers")
      val now             = System.currentTimeMillis()
      val transfersNumber = EcBlock.MaxWithdrawals + 1
      val txns = Seq(
        d.ChainContract.enableStrictTransfers(d.blockchain.height + 1),
        // Transfers
        TxHelpers.reissue(d.nativeTokenId, d.chainContractAccount, nativeTokensClAmount * transfersNumber),
        TxHelpers.transfer(d.chainContractAccount, transferSenderAccount.toAddress, nativeTokensClAmount * transfersNumber, d.nativeTokenId)
      ) ++ (0 until transfersNumber).map { i =>
        mkNativeTransfer(d, now + i)
      }

      d.appendBlock(txns*)

      step(s"Start a new epoch of miner ${second.address}")
      d.advanceNewBlocks(second)
      d.advanceConsensusLayerChanged()

      val block = d
        .createEcBlockBuilder("0", second)
        .updateBlock { b =>
          b.copy(withdrawals = (0 until EcBlock.MaxWithdrawals).map(Withdrawal(_, destElAddress, nativeTokensElAmount)).toVector)
        }
        .buildAndSetLogs()

      d.appendMicroBlock(
        d.ChainContract.extendMainChain(second.account, block, lastC2ETransferIndex = EcBlock.MaxWithdrawals - 1)
      )
      d.advanceConsensusLayerChanged()

      step(s"Receive block ${block.hash}")
      d.receiveNetworkBlock(block, second.account)
      withClue("Full EL block validation:") {
        d.triggerScheduledTasks()
        if (d.pollSentNetworkBlock().isEmpty) fail(s"${block.hash} should not be ignored")
      }
    }
  }

  private def transferFuncTest(
      destElAddressHex: String,
      transferAmount: Int = 1_000_000,
      assetId: Option[Asset] = None,
      queueSize: Int = 0,
      register: Boolean = false
  ): Either[Throwable, BlockId] = withExtensionDomain(defaultSettings.copy(enableTokenTransfersEpoch = if (register) 0 else Int.MaxValue)) { d =>
    val amount = Long.MaxValue / 2
    d.appendMicroBlock(
      issueAssetTxn,
      TxHelpers.reissue(d.nativeTokenId, d.chainContractAccount, amount),
      TxHelpers.transfer(d.chainContractAccount, transferSenderAccount.toAddress, amount, d.nativeTokenId)
    )

    assetId
      .collect { case asset: Asset.IssuedAsset if register => d.ChainContract.registerAsset(asset, mkRandomEthAddress(), 8) }
      .foreach(d.appendMicroBlock(_))

    if (queueSize > 0) d.appendMicroBlock(TxHelpers.data(d.chainContractAccount, Seq(IntegerDataEntry("nativeTransfersCount", queueSize))))

    d.appendMicroBlockE(d.ChainContract.transferUnsafe(transferSenderAccount, destElAddressHex, assetId.getOrElse(d.nativeTokenId), transferAmount))
  }
}
