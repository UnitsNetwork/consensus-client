package units

import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.state.IntegerDataEntry
import com.wavesplatform.test.produce
import com.wavesplatform.transaction.{Asset, TxHelpers}

class C2ETransfersTestSuite extends BaseIntegrationTestSuite {
  private val transferSenderAccount  = TxHelpers.secondSigner
  private val validTransferRecipient = "1111111111111111111111111111111111111111"
  private val unrelatedAsset         = TxHelpers.issue(issuer = transferSenderAccount)

  override protected val defaultSettings: TestSettings = super.defaultSettings.copy(
    additionalBalances = List(AddrWithBalance(transferSenderAccount.toAddress))
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

  "Deny an unregistered asset" in forAll(
    Table(
      "invalid asset"                        -> "message",
      Asset.Waves                            -> "Can't find in the registry: WAVES",
      Asset.IssuedAsset(unrelatedAsset.id()) -> s"Can't find in the registry: ${unrelatedAsset.id()}"
    )
  ) { case (assetId, message) =>
    transferFuncTest(validTransferRecipient, assetId = Some(assetId)) should produce(message)
  }

  "Allow a registered asset" in forAll(
    Table(
      "invalid asset"                        -> "message",
      Asset.Waves                            -> "Can't find in the registry: WAVES",
      Asset.IssuedAsset(unrelatedAsset.id()) -> s"Can't find in the registry: ${unrelatedAsset.id()}"
    )
  ) { case (assetId, message) =>
    transferFuncTest(validTransferRecipient, assetId = Some(assetId), register = true) should beRight
  }

  private def transferFuncTest(
      destElAddressHex: String,
      transferAmount: Int = 1_000_000,
      assetId: Option[Asset] = None,
      queueSize: Int = 0,
      register: Boolean = false
  ): Either[Throwable, BlockId] = withExtensionDomain() { d =>
    val amount = Long.MaxValue / 2
    d.appendMicroBlock(
      unrelatedAsset,
      TxHelpers.reissue(d.nativeTokenId, d.chainContractAccount, amount),
      TxHelpers.transfer(d.chainContractAccount, transferSenderAccount.toAddress, amount, d.nativeTokenId)
    )
    assetId.filter(_ => register).foreach(assetId => d.appendMicroBlock(d.ChainContract.registerAsset(assetId, mkRandomEthAddress(), 8)))
    if (queueSize > 0) d.appendMicroBlock(TxHelpers.data(d.chainContractAccount, Seq(IntegerDataEntry("nativeTransfersCount", queueSize))))

    d.appendMicroBlockE(d.ChainContract.transferUnsafe(transferSenderAccount, destElAddressHex, assetId.getOrElse(d.nativeTokenId), transferAmount))
  }
}
