package units

import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.state.IntegerDataEntry
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.{Asset, TxHelpers}
import monix.execution.atomic.AtomicInt
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.{EthSendTransaction, TransactionReceipt}
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import com.wavesplatform.api.NodeHttpApi.ErrorResponse
import units.client.contract.{ChainContractClient, ContractBlock}
import units.docker.EcContainer
import units.el.{BridgeMerkleTree, E2CTopics, Erc20Client, TERC20Client}
import units.eth.EthAddress

import scala.annotation.tailrec
import scala.jdk.OptionConverters.RichOptional

class SingleFailedAssetTransferTestSuite extends BaseDockerTestSuite {
  private val clRecipient     = clRichAccount1
  private val elSender        = elRichAccount1
  private val elSenderAddress = elRichAddress1

  private val issueAssetDecimals = 8.toByte
  private lazy val issueAsset    = chainContract.getRegisteredAsset(1) // 0 is WAVES

  private val userAmount = BigDecimal("1")

  private val gasProvider       = new DefaultGasProvider
  private lazy val txnManager   = new RawTransactionManager(ec1.web3j, elSender, EcContainer.ChainId, 20, 2000)
  private lazy val wwaves       = new Erc20Client(ec1.web3j, WWavesAddress, txnManager, gasProvider)
  private lazy val terc20       = new Erc20Client(ec1.web3j, TErc20Address, txnManager, gasProvider)
  private lazy val terc20client = new TERC20Client(ec1.web3j, TErc20Address, txnManager, gasProvider)

  private val issuedE2CAmount   = UnitsConvert.toAtomic(userAmount * 2, TErc20Decimals)
  private val nativeE2CAmount   = UnitsConvert.toAtomic(userAmount, NativeTokenElDecimals)
  private val burnE2CAmount     = UnitsConvert.toAtomic(userAmount, TErc20Decimals)
  private val leftoverE2CAmount = UnitsConvert.toAtomic(userAmount, TErc20Decimals)
  private val wavesE2CAmount    = UnitsConvert.toAtomic(userAmount, WwavesDecimals)

  private lazy val currNonce =
    AtomicInt(ec1.web3j.ethGetTransactionCount(elSenderAddress.hex, DefaultBlockParameterName.PENDING).send().getTransactionCount.intValueExact())
  def nextNonce: Int = currNonce.getAndIncrement()

  "Mining continues after 2 equivalent transfers: 1 successful and 1 failed" in {
    withClue("Reduce Standard Bridge balance to make C2E transfer fail") {
      waitForTxn(terc20client.sendBurn(StandardBridgeAddress, burnE2CAmount.bigInteger, nextNonce))
      terc20.getBalance(StandardBridgeAddress) shouldBe leftoverE2CAmount
    }

    step("Initiate C2E transfers")
    val c2eRecipientAddress = EthAddress.unsafeFrom("0xAAAA00000000000000000000000000000000AAAA")

    val recipientAssetBalanceBeforeC2ETransfer = clRecipientAssetBalance
    def mkC2ETransferTxn(asset: Asset, decimals: Byte): InvokeScriptTransaction =
      ChainContract.transfer(
        clRecipient,
        c2eRecipientAddress,
        asset,
        UnitsConvert.toWavesAtomic(userAmount, decimals)
      )

    val c2eTransferTxns = List(
      mkC2ETransferTxn(Asset.Waves, WavesDecimals),
      mkC2ETransferTxn(issueAsset, issueAssetDecimals),
      mkC2ETransferTxn(issueAsset, issueAssetDecimals),
      mkC2ETransferTxn(chainContract.nativeTokenId, NativeTokenClDecimals)
    )
    c2eTransferTxns.foreach(waves1.api.broadcast)
    c2eTransferTxns.map(txn => waves1.api.waitForSucceeded(txn.id()))

    eventually {
      withClue("Issued asset: the sender balance has been reduced even though the transfer has failed") {
        val balanceAfter = clRecipientAssetBalance
        // Note: 1 for successful, 1 for failed
        balanceAfter shouldBe (recipientAssetBalanceBeforeC2ETransfer - UnitsConvert.toWavesAtomic(userAmount * 2, issueAssetDecimals))
      }

      withClue("Issued asset: the transfer has failed") {
        terc20.getBalance(c2eRecipientAddress) shouldBe leftoverE2CAmount
      }

      withClue("Native token: the other transfers have succeeded") {
        val balanceAfter = ec1.web3j.ethGetBalance(c2eRecipientAddress.hex, DefaultBlockParameterName.PENDING).send().getBalance
        BigInt(balanceAfter) shouldBe nativeE2CAmount
      }

      withClue("WAVES: the other transfers have succeeded") {
        wwaves.getBalance(c2eRecipientAddress) shouldBe wavesE2CAmount
      }
    }

    step("Mining continues")
    val clHeightAfterTransfers = waves1.api.height()
    val elHeightAfterTransfers = ec1.web3j.ethBlockNumber().send().getBlockNumber.longValueExact()

    withClue("CL height grows") {
      waves1.api.waitForHeight(clHeightAfterTransfers + 2)
    }
    withClue("EL height grows") {
      chainContract.waitForHeight(elHeightAfterTransfers + 2)
    }

    step("Sender can get their funds back from a failed transfer using a chain contract method")
    val refundAmount        = UnitsConvert.toWavesAtomic(userAmount, issueAssetDecimals)
    val balanceBeforeRefund = clRecipientAssetBalance

    val failedTransferIndex         = 2
    val expectedFailedTransfersRoot = BridgeMerkleTree.getFailedTransfersRootHash(Seq(failedTransferIndex))

    @tailrec
    def loop(cb: ContractBlock): ContractBlock =
      if (java.util.Arrays.equals(cb.failedC2ETransfersRootHash, expectedFailedTransfersRoot)) cb
      else
        chainContract.getBlock(cb.parentHash) match {
          case Some(parent) => loop(parent)
          case None         => fail("Failed to locate block with failed transfer data")
        }

    val blockWithFailedTransfer = loop(chainContract.getLastBlockMeta(ChainContractClient.DefaultMainChainId).value)

    val failedTransferProof = BridgeMerkleTree
      .mkFailedTransferProofs(List(failedTransferIndex), transferIndex = 0)
      .reverse

    val refundInvoke = ChainContract.refundFailedC2ETransfer(
      sender = clRecipient,
      blockHash = blockWithFailedTransfer.hash,
      merkleProof = failedTransferProof,
      failedTransferIndex = failedTransferIndex,
      transferIndexInBlock = 0
    )
    waves1.api.broadcastAndWait(refundInvoke)

    withClue("Issued asset: balance after refund increased by the returned funds") {
      eventually {
        clRecipientAssetBalance shouldBe (balanceBeforeRefund + refundAmount)
      }
    }

    step("Attempting to refund the same failed transfer again")
    val refundInvoke2 = ChainContract.refundFailedC2ETransfer(
      sender = clRecipient,
      blockHash = blockWithFailedTransfer.hash,
      merkleProof = failedTransferProof,
      failedTransferIndex = failedTransferIndex,
      transferIndexInBlock = 0
    )
    val res2 = waves1.api.broadcast(refundInvoke2)

    val expectedError = ErrorResponse(306, "Error while executing dApp: The funds for this transfer have already been refunded")
    res2 shouldBe Left(expectedError)
  }

  private def clRecipientAssetBalance: Long = waves1.api.balance(clRecipient.toAddress, issueAsset)

  private def waitForTxn(txnResult: EthSendTransaction): TransactionReceipt = eventually {
    ec1.web3j.ethGetTransactionReceipt(txnResult.getTransactionHash).send().getTransactionReceipt.toScala.value
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    deploySolidityContracts()

    step("Enable token transfers")
    val activationEpoch = waves1.api.height() + 1
    waves1.api.broadcastAndWait(
      ChainContract.enableTokenTransfersWithWaves(
        StandardBridgeAddress,
        WWavesAddress,
        activationEpoch = activationEpoch
      )
    )

    step("Set strict C2E transfers feature activation epoch")
    waves1.api.broadcastAndWait(
      TxHelpers.dataEntry(
        chainContractAccount,
        IntegerDataEntry("strictC2ETransfersActivationEpoch", activationEpoch)
      )
    )

    step("Wait for features activation")
    waves1.api.waitForHeight(activationEpoch)

    step("Register asset")
    waves1.api.broadcastAndWait(ChainContract.issueAndRegister(TErc20Address, TErc20Decimals, "TERC20", "Test ERC20 token", issueAssetDecimals))
    eventually {
      standardBridge.isRegistered(TErc20Address, ignoreExceptions = true) shouldBe true
    }

    step("Send allowances")
    List(
      terc20.sendApprove(StandardBridgeAddress, issuedE2CAmount, nextNonce),
      wwaves.sendApprove(StandardBridgeAddress, wavesE2CAmount, nextNonce)
    ).foreach(waitFor)

    step("Initiate E2C transfers")
    val e2cNativeTxn = nativeBridge.sendSendNative(elSender, clRecipient.toAddress, nativeE2CAmount, nextNonce)
    val e2cIssuedTxn = standardBridge.sendBridgeErc20(elSender, TErc20Address, clRecipient.toAddress, issuedE2CAmount, nextNonce)
    val e2cWavesTxn  = standardBridge.sendBridgeErc20(elSender, WWavesAddress, clRecipient.toAddress, wavesE2CAmount, nextNonce)

    chainContract.waitForEpoch(waves1.api.height() + 1) // Bypass rollbacks
    val e2cReceipts = List(e2cNativeTxn, e2cIssuedTxn, e2cWavesTxn).map { txn =>
      eventually {
        val hash = txn.getTransactionHash
        withClue(s"$hash: ") {
          ec1.web3j.ethGetTransactionReceipt(hash).send().getTransactionReceipt.toScala.value
        }
      }
    }

    withClue("E2C should be on same height, can't continue the test: ") {
      val e2cHeights = e2cReceipts.map(_.getBlockNumber.intValueExact()).toSet
      e2cHeights.size shouldBe 1
    }

    val e2cBlockHash = BlockHash(e2cReceipts.head.getBlockHash)
    log.debug(s"Block with e2c transfers: $e2cBlockHash")

    val e2cLogsInBlock = ec1.engineApi
      .getLogs(e2cBlockHash, List(NativeBridgeAddress, StandardBridgeAddress))
      .explicitGet()
      .filter(_.topics.intersect(E2CTopics).nonEmpty)

    withClue("We have logs for all transactions: ") {
      e2cLogsInBlock.size shouldBe e2cReceipts.size
    }

    step(s"Wait block $e2cBlockHash with transfers on contract")
    val e2cBlockConfirmationHeight = eventually {
      chainContract.getBlock(e2cBlockHash).value.height
    }

    step(s"Wait for block $e2cBlockHash ($e2cBlockConfirmationHeight) finalization")
    eventually {
      val currFinalizedHeight = chainContract.getFinalizedBlock.height
      step(s"Current finalized height: $currFinalizedHeight")
      currFinalizedHeight should be >= e2cBlockConfirmationHeight
    }

    step("Broadcast withdrawAsset transactions")
    val recipientAssetBalanceBefore = clRecipientAssetBalance

    def mkE2CWithdrawTxn(transferIndex: Int, asset: Asset, amount: BigDecimal, decimals: Byte): InvokeScriptTransaction =
      ChainContract.withdrawAsset(
        sender = clRecipient,
        blockHash = e2cBlockHash,
        merkleProof = BridgeMerkleTree.mkTransferProofs(e2cLogsInBlock, transferIndex).explicitGet().reverse,
        transferIndexInBlock = transferIndex,
        amount = UnitsConvert.toWavesAtomic(amount, decimals),
        asset = asset
      )

    val e2cWithdrawTxns = List(
      mkE2CWithdrawTxn(0, chainContract.nativeTokenId, userAmount, NativeTokenClDecimals),
      mkE2CWithdrawTxn(1, issueAsset, userAmount * 2, issueAssetDecimals),
      mkE2CWithdrawTxn(2, Asset.Waves, userAmount, WavesDecimals)
    )

    e2cWithdrawTxns.foreach(waves1.api.broadcast)
    e2cWithdrawTxns.foreach(txn => waves1.api.waitForSucceeded(txn.id()))

    withClue("Assets received after E2C: ") {
      withClue("Issued asset: the balance was initially sufficient on CL") {
        val balanceAfter = clRecipientAssetBalance
        balanceAfter shouldBe (recipientAssetBalanceBefore + UnitsConvert.toWavesAtomic(userAmount * 2, issueAssetDecimals))
      }
      withClue("Issued asset: the StandardBridge balance was initially sufficient on EL") {
        terc20.getBalance(StandardBridgeAddress) shouldBe issuedE2CAmount
      }
    }
  }
}
