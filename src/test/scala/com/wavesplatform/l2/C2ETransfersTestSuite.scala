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

  override protected val defaultSettings: TestSettings = TestSettings.Default.copy(
    additionalBalances = List(AddrWithBalance(transferSenderAccount.toAddress))
  )

  "Pass valid address with valid payment" in {
    transferFuncTest(validTransferRecipient) should beRight
  }

  "Deny invalid address" in forAll(
    Table(
      "invalid address"                            -> "message",
      ""                                           -> "Address should have 40 characters",
      "0x"                                         -> "Address should have 40 characters",
      "0x0000000000000000000000000000000000000002" -> "Address should have 40 characters",
      "000000000000000000000000000000000000000003" -> "Address should have 40 characters",
      "0x00000000000000000000000000000000000001"   -> "Unrecognized character: x",
      "QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ"   -> "Unrecognized character: q"
    )
  ) { case (address, message) =>
    transferFuncTest(address) should produce(message)
  }

  "Deny invalid payment amount" in forAll(
    Table(
      ("invalid payment amount", "initial queue size", "message"),
      (1, 0, "should be >= 1000000 for queue size of 1"),
      (1_000_000, 1600, "should be >= 100000000 for queue size of 1601"),
      (1_000_000_001, 6401, "Transfers denied for queue size of 6402")
    )
  ) { case (transferAmount, initQueueSize, message) =>
    transferFuncTest(validTransferRecipient, transferAmount, queueSize = initQueueSize) should produce(message)
  }

  "Deny invalid asset" in forAll(
    Table(
      "invalid token"                        -> "message",
      Asset.Waves                            -> "in the payment, got Waves",
      Asset.IssuedAsset(unrelatedAsset.id()) -> s"in the payment, got ${unrelatedAsset.id()}"
    )
  ) { case (tokenId, message) =>
    transferFuncTest(validTransferRecipient, tokenId = Some(tokenId)) should produce(message)
  }

  private def transferFuncTest(
      destElAddressHex: String,
      transferAmount: Int = 1_000_000,
      tokenId: Option[Asset] = None,
      queueSize: Int = 0
  ): Either[Throwable, BlockId] =
    withConsensusClient() { (d, _) =>
      val transferSenderTokenAmount = Long.MaxValue / 2
      d.appendMicroBlock(
        unrelatedAsset,
        TxHelpers.reissue(d.token, chainContractAccount, transferSenderTokenAmount),
        TxHelpers.transfer(chainContractAccount, transferSenderAccount.toAddress, transferSenderTokenAmount, d.token)
      )
      if (queueSize > 0) d.appendMicroBlock(TxHelpers.data(chainContractAccount, Seq(IntegerDataEntry("nativeTransfersCount", queueSize))))

      d.appendMicroBlockE(chainContract.transferUnsafe(transferSenderAccount, destElAddressHex, tokenId.getOrElse(d.token), transferAmount))
    }
}
