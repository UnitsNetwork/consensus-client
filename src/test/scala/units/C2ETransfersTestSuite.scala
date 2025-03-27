package units

import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.state.IntegerDataEntry
import com.wavesplatform.test.produce
import com.wavesplatform.transaction.{Asset, TxHelpers}

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
