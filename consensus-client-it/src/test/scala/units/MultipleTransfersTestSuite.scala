package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.{Asset, TxHelpers}
import org.web3j.crypto.{Credentials, RawTransaction}
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.{EthSendTransaction, TransactionReceipt}
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.utils.Convert
import units.bridge.{TERC20, UnitsMintableERC20}
import units.client.contract.HasConsensusLayerDappTxHelpers.DefaultFees
import units.docker.EcContainer
import units.el.{BridgeMerkleTree, E2CTopics, EvmEncoding}
import units.eth.EthAddress

import java.math.BigInteger
import scala.jdk.OptionConverters.RichOptional
import scala.util.chaining.*

class MultipleTransfersTestSuite extends BaseDockerTestSuite {
  private val clAssetOwner    = clRichAccount2
  private val clRecipient     = clRichAccount1
  private val elSender        = elRichAccount1
  private val elSenderAddress = elRichAddress1

  private val issueAssetDecimals = 8.toByte
  private val issueAssetTxn      = TxHelpers.issue(clAssetOwner, decimals = issueAssetDecimals)
  private val issueAsset         = IssuedAsset(issueAssetTxn.id())

  private val userAmount = BigDecimal("1")

  private val tenGwei = BigInt(Convert.toWei("10", Convert.Unit.GWEI).toBigIntegerExact)

  private val gasProvider     = new DefaultGasProvider
  private lazy val txnManager = new RawTransactionManager(ec1.web3j, elSender, EcContainer.ChainId, 20, 2000)
  private lazy val wwaves     = UnitsMintableERC20.load(WWavesAddress.hex, ec1.web3j, txnManager, gasProvider)
  private lazy val terc20     = TERC20.load(TErc20Address.hex, ec1.web3j, elSender, gasProvider)

  "Checking balances in E2C2E transfers" in {
    val initNonce =
      ec1.web3j.ethGetTransactionCount(elSenderAddress.hex, DefaultBlockParameterName.PENDING).send().getTransactionCount.intValueExact()

    val nativeE2CAmount = UnitsConvert.toAtomic(userAmount, NativeTokenElDecimals)
    val issuedE2CAmount = UnitsConvert.toAtomic(userAmount, TErc20Decimals)
    val wavesE2CAmount  = UnitsConvert.toAtomic(userAmount, WwavesDecimals)

    step("Send allowances")
    List(
      sendApproveErc20(terc20, issuedE2CAmount, initNonce),
      sendApproveErc20(wwaves, wavesE2CAmount, initNonce + 1)
    ).foreach(waitFor)

    step("Initiate E2C transfers")
    val e2cNativeTxn = nativeBridge.sendSendNative(elSender, clRecipient.toAddress, nativeE2CAmount, Some(initNonce + 2))
    val e2cIssuedTxn = standardBridge.sendBridgeErc20(elSender, TErc20Address, clRecipient.toAddress, issuedE2CAmount, Some(initNonce + 3))
    val e2cWavesTxn  = standardBridge.sendBridgeErc20(elSender, WWavesAddress, clRecipient.toAddress, wavesE2CAmount, Some(initNonce + 4))

    chainContract.waitForHeight(ec1.web3j.ethBlockNumber().send().getBlockNumber.intValueExact() + 2) // Bypass rollbacks

    val e2cReceipts = List(e2cIssuedTxn, e2cIssuedTxn, e2cWavesTxn).map { txn =>
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
      .getLogs(e2cBlockHash, List(NativeBridgeAddress, StandardBridgeAddress), Nil)
      .explicitGet()
      .filter(_.topics.intersect(E2CTopics).nonEmpty)

    withClue("We have logs for all transactions: ") {
      e2cLogsInBlock.size shouldBe 3
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
    val recipientWavesBalanceBefore       = clRecipientWavesBalance
    val recipientAssetBalanceBefore       = clRecipientAssetBalance
    val recipientNativeTokenBalanceBefore = clRecipientNativeTokenBalance

    def mkE2CWithdrawTxn(transferIndex: Int, asset: Asset, decimals: Byte) = ChainContract.withdrawAsset(
      sender = clRecipient,
      blockHash = e2cBlockHash,
      merkleProof = BridgeMerkleTree.mkTransferProofs(e2cLogsInBlock, transferIndex).explicitGet().reverse,
      transferIndexInBlock = transferIndex,
      amount = UnitsConvert.toWavesAtomic(userAmount, decimals),
      asset = asset
    )

    val e2cWithdrawTxns = List(
      mkE2CWithdrawTxn(0, chainContract.nativeTokenId, NativeTokenClDecimals),
      mkE2CWithdrawTxn(1, issueAsset, issueAssetDecimals),
      mkE2CWithdrawTxn(2, Asset.Waves, WavesDecimals)
    )

    e2cWithdrawTxns.foreach(waves1.api.broadcast)
    e2cWithdrawTxns.foreach(txn => waves1.api.waitForSucceeded(txn.id()))

    withClue("Assets received after E2C: ") {
      withClue("Native token: ") {
        val balanceAfter = clRecipientNativeTokenBalance
        balanceAfter shouldBe (recipientNativeTokenBalanceBefore + UnitsConvert.toWavesAtomic(userAmount, NativeTokenClDecimals))
      }

      withClue("Issued asset: ") {
        val balanceAfter = clRecipientAssetBalance
        balanceAfter shouldBe (recipientAssetBalanceBefore + UnitsConvert.toWavesAtomic(userAmount, issueAssetDecimals))
      }

      withClue("WAVES: ") {
        val balanceAfter = clRecipientWavesBalance
        val fee          = DefaultFees.ChainContract.withdrawFee * e2cWithdrawTxns.size
        balanceAfter shouldBe (recipientWavesBalanceBefore + UnitsConvert.toWavesAtomic(userAmount, WavesDecimals) - fee)
      }
    }

    step("Initiate C2E transfers")
    val c2eRecipientAddress = EthAddress.unsafeFrom("0xAAAA00000000000000000000000000000000AAAA")

    def mkC2ETransferTxn(asset: Asset, decimals: Byte) = ChainContract.transfer(
      clRecipient,
      c2eRecipientAddress,
      asset,
      UnitsConvert.toWavesAtomic(userAmount, decimals)
    )

    val c2eTransferTxns = List(
      mkC2ETransferTxn(chainContract.nativeTokenId, NativeTokenClDecimals),
      mkC2ETransferTxn(issueAsset, issueAssetDecimals),
      mkC2ETransferTxn(Asset.Waves, WavesDecimals)
    )

    c2eTransferTxns.foreach(waves1.api.broadcast)
    val c2eTransferTxnResults = c2eTransferTxns.map(txn => waves1.api.waitForSucceeded(txn.id()))

    withClue("C2E should be on same height, can't continue the test: ") {
      val c2eHeights = c2eTransferTxnResults.map(_.height).toSet
      c2eHeights.size shouldBe 1
    }

    withClue("Assets received after C2E: ") {
      eventually {
        withClue("Native token: ") {
          val balanceAfter = ec1.web3j.ethGetBalance(c2eRecipientAddress.hex, DefaultBlockParameterName.PENDING).send().getBalance
          BigInt(balanceAfter) shouldBe nativeE2CAmount
        }

        withClue("Issued asset: ") {
          getBalance(terc20, c2eRecipientAddress.hex) shouldBe issuedE2CAmount
        }

        withClue("WAVES: ") {
          getBalance(wwaves, c2eRecipientAddress.hex) shouldBe wavesE2CAmount
        }
      }
    }
  }

  private def getBalance(erc20Contract: TERC20 | UnitsMintableERC20, account: String): BigInt =
    erc20Contract match {
      case contract: TERC20             => contract.call_balanceOf(account).send()
      case contract: UnitsMintableERC20 => contract.call_balanceOf(account).send()
    }

  private def sendApproveErc20(erc20Contract: TERC20 | UnitsMintableERC20, elAmount: BigInt, nonce: Int): EthSendTransaction = {
    val spender = StandardBridgeAddress.toString

    val (to, funcCall) = erc20Contract match {
      case contract: TERC20 =>
        log.debug(s"Balance: ${contract.call_balanceOf(elSender.getAddress).send().longValue()}")
        (contract.getContractAddress, contract.send_approve(spender, elAmount.bigInteger).encodeFunctionCall())
      case contract: UnitsMintableERC20 =>
        log.debug(s"Balance: ${contract.call_balanceOf(elSender.getAddress).send().longValue()}")
        (contract.getContractAddress, contract.send_approve(spender, elAmount.bigInteger).encodeFunctionCall())
    }

    val rawTxn = RawTransaction.createTransaction(
      1337L, // TODO
      BigInteger.valueOf(nonce),
      gasProvider.getGasLimit,
      to,
      BigInteger.ZERO,
      funcCall,
      BigInteger.ONE,
      gasProvider.getGasPrice
    )

    log.debug(s"Send ${elSender.getAddress} approval for StandardBridge, nonce: $nonce")
    val r = txnManager.signAndSend(rawTxn)
    if (r.hasError) throw new TransactionException(s"Can't send approve: ${r.getError}, ${r.getError.getMessage}")
    r
  }

  private def waitFor(txn: EthSendTransaction): TransactionReceipt = eventually {
    ec1.web3j.ethGetTransactionReceipt(txn.getTransactionHash).send().getTransactionReceipt.toScala.value.tap { r =>
      if (!r.isStatusOK) {
        fail(s"Expected successful send_approve, got: tx=${EvmEncoding.decodeRevertReason(r.getRevertReason)}")
      }
    }
  }

  private def waitForTransfer(sender: Credentials, erc20Address: EthAddress, ethAmount: BigInt, txn: EthSendTransaction): TransactionReceipt =
    eventually {
      val r = ec1.web3j.ethGetTransactionReceipt(txn.getTransactionHash).send().getTransactionReceipt.toScala.value
      if (!r.isStatusOK) {
        val revertReason = standardBridge.getRevertReasonForBridgeErc20(sender, erc20Address, clRecipient.toAddress, ethAmount)
        fail(s"Expected successful sendBridgeErc20, got: tx=${EvmEncoding.decodeRevertReason(r.getRevertReason)}, revertReason=$revertReason")
      }
      r
    }

  private def sendBridgeErc20(sender: Credentials, erc20Address: EthAddress, ethAmount: BigInt): TransactionReceipt = {
    val txnResult = standardBridge.sendBridgeErc20(sender, erc20Address, clRecipient.toAddress, ethAmount)
    // To overcome a failed block confirmation in a new epoch issue

    eventually {
      val r = ec1.web3j.ethGetTransactionReceipt(txnResult.getTransactionHash).send().getTransactionReceipt.toScala.value
      if (!r.isStatusOK) {
        val revertReason = standardBridge.getRevertReasonForBridgeErc20(sender, erc20Address, clRecipient.toAddress, ethAmount)
        fail(s"Expected successful sendBridgeErc20, got: tx=${EvmEncoding.decodeRevertReason(r.getRevertReason)}, revertReason=$revertReason")
      }
      r
    }
  }

  private def clRecipientWavesBalance: Long       = waves1.api.balance(clRecipient.toAddress, Asset.Waves)
  private def clRecipientAssetBalance: Long       = waves1.api.balance(clRecipient.toAddress, issueAsset)
  private def clRecipientNativeTokenBalance: Long = waves1.api.balance(clRecipient.toAddress, chainContract.nativeTokenId)

  override def beforeAll(): Unit = {
    super.beforeAll()
    deploySolidityContracts()

    step("Prepare: issue CL asset")
    waves1.api.broadcastAndWait(issueAssetTxn)

    // TODO: Use issueAndRegister instead
    step("Prepare: move assets for testing purposes")
    waves1.api.broadcast(
      TxHelpers.transfer(clAssetOwner, chainContractAddress, UnitsConvert.toWavesAtomic(userAmount, issueAssetDecimals), issueAsset)
    )

    step("Enable token transfers")
    val activationEpoch = waves1.api.height() + 1
    waves1.api.broadcastAndWait(
      ChainContract.enableTokenTransfersWithWaves(
        StandardBridgeAddress,
        WWavesAddress,
        activationEpoch = activationEpoch
      )
    )
    waves1.api.waitForHeight(activationEpoch)

    step("Register asset")
    val txn = ChainContract.registerAsset(issueAsset, TErc20Address, TErc20Decimals) // TODO: Issue and register
    waves1.api.broadcastAndWait(txn)
    eventually {
      standardBridge.isRegistered(TErc20Address) shouldBe true
    }
  }
}
