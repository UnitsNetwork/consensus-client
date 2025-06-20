package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.utils.EthEncoding
import org.web3j.crypto.Credentials
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.utils.Convert
import units.bridge.ERC20
import units.docker.EcContainer
import units.el.{BridgeMerkleTree, E2CTopics, Erc20Client, EvmEncoding}
import units.eth.EthAddress

import scala.jdk.OptionConverters.RichOptional

class StandardBridgeTestSuite extends BaseDockerTestSuite {
  private val clAssetOwner    = clRichAccount2
  private val clRecipient     = clRichAccount1
  private val elSender        = elRichAccount1
  private val elSenderAddress = elRichAddress1

  private val issueAssetDecimals = 8.toByte
  private lazy val issueAsset    = chainContract.getRegisteredAsset(1) // 0 is WAVES

  private val userAmount = 1
  private val clAmount   = UnitsConvert.toWavesAtomic(userAmount, issueAssetDecimals)
  private val elAmount   = UnitsConvert.toAtomic(userAmount, TErc20Decimals)

  private val testTransfers  = 2
  private val enoughClAmount = clAmount * testTransfers
  private val enoughElAmount = elAmount * testTransfers

  private val tenGwei = BigInt(Convert.toWei("10", Convert.Unit.GWEI).toBigIntegerExact)

  private val gasProvider     = new DefaultGasProvider
  private lazy val txnManager = new RawTransactionManager(ec1.web3j, elSender, EcContainer.ChainId, 20, 2000)
  private lazy val wwaves     = new Erc20Client(ec1.web3j, WWavesAddress, txnManager, gasProvider)
  private lazy val terc20     = new Erc20Client(ec1.web3j, TErc20Address, txnManager, gasProvider)

  "Negative" - {
    def test(amount: BigInt, expectedError: String): Unit = {
      val e = standardBridge.getRevertReasonForBridgeErc20(elSender, TErc20Address, clRecipient.toAddress, amount)
      e should include(expectedError)
    }

    // TODO: Because we have to get a token from dictionary before amount checks. Fix with unit tests for contract
    "Amount should be between 10 and MAX_AMOUNT_IN_WEI Gwei" ignore {
      withClue("1. Less than 10 Gwei: ") {
        test(1, "Sent value 1 must be greater or equal to 10000000000")
      }

      withClue("2. More than MAX_AMOUNT_IN_WEI: ") {
        val maxAmountInWei = BigInt(Long.MaxValue) * tenGwei
        val biggerAmount   = (maxAmountInWei / tenGwei + 1) * tenGwei
        test(biggerAmount, s"Sent value $biggerAmount must be less or equal to $maxAmountInWei")
      }
    }
  }

  "Positive" - {
    "Check Asset transfer EL->CL->EL" in {
      terc20E2CTransfer(elAmount, clAmount)

      step("Initiate CL->EL transfer. Broadcast ChainContract.transfer transaction")
      val elRecipientAddress = EthAddress.unsafeFrom("0xAAAA00000000000000000000000000000000AAAA")

      val returnUserAmount = BigDecimal(userAmount) / 2
      val returnClAmount   = UnitsConvert.toWavesAtomic(returnUserAmount, issueAssetDecimals)
      val returnElAmount   = UnitsConvert.toAtomic(returnUserAmount, TErc20Decimals)

      waves1.api.broadcastAndWait(ChainContract.transfer(clRecipient, elRecipientAddress, issueAsset, returnClAmount))
      withClue("Assets received: ") {
        eventually {
          withClue("elRecipient: ") {
            terc20.getBalance(elRecipientAddress) shouldBe returnElAmount
          }
          withClue("StandardBridge: ") {
            terc20.getBalance(StandardBridgeAddress) shouldBe returnElAmount
          }
        }
      }
    }

    "Check Waves transfer CL->EL->CL" in {
      withClue("4. Transfer Waves C>E>C") {
        val transferUserAmount = 55
        // Same for EL and CL, because has same decimals
        val transferAmount = UnitsConvert.toWavesAtomic(transferUserAmount, WavesDecimals)

        val chainContractBalanceBefore = waves1.api.balance(chainContractAddress, Asset.Waves)

        def transferTxn: InvokeScriptTransaction = ChainContract.transfer(clRichAccount1, elSenderAddress, Asset.Waves, transferAmount)

        val elSenderBalanceBefore   = wwaves.getBalance(elSenderAddress)
        val wwavesTotalSupplyBefore = wwaves.totalSupply

        waves1.api.broadcastAndWait(transferTxn)
        waves1.api.balance(chainContractAddress, Asset.Waves) shouldBe chainContractBalanceBefore + transferAmount

        eventually {
          wwaves.getBalance(elSenderAddress) shouldBe elSenderBalanceBefore + transferAmount
          wwaves.totalSupply shouldEqual wwavesTotalSupplyBefore + transferAmount
        }

        val returnAmount = 32_00000000L

        step("Send allowance")
        waitFor(wwaves.sendApprove(StandardBridgeAddress, returnAmount))

        step("Broadcast StandardBridge.sendBridgeErc20 transaction")
        val sendTxnReceipt = sendBridgeErc20(elSender, WWavesAddress, returnAmount)

        val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
        step(s"Block with transaction: $blockHash")

        val logsInBlock = ec1.engineApi
          .getLogs(blockHash, List(NativeBridgeAddress, StandardBridgeAddress))
          .explicitGet()
          .filter(_.topics.intersect(E2CTopics).nonEmpty)

        val sendTxnLogIndex = logsInBlock.indexWhere(_.transactionHash == sendTxnReceipt.getTransactionHash)
        sendTxnLogIndex shouldBe >=(0)

        val transferProofs = BridgeMerkleTree.mkTransferProofs(logsInBlock, sendTxnLogIndex).explicitGet().reverse

        step(s"Wait for block $blockHash on contract")
        val blockConfirmationHeight = eventually {
          chainContract.getBlock(blockHash).value.height
        }

        step(s"Wait for block $blockHash ($blockConfirmationHeight) finalization")
        eventually {
          val currFinalizedHeight = chainContract.getFinalizedBlock.height
          step(s"Current finalized height: $currFinalizedHeight")
          currFinalizedHeight should be >= blockConfirmationHeight
        }

        withClue("3. Assets received: ") {
          step(
            s"Broadcast withdrawAsset transaction: transferIndexInBlock=$sendTxnLogIndex, amount=$returnAmount, " +
              s"merkleProof={${transferProofs.map(EthEncoding.toHexString).mkString(",")}}"
          )

          def receiverBalance: Long = waves1.api.balance(clRecipient.toAddress, Asset.Waves)

          val receiverBalanceBefore = receiverBalance

          val withdrawTxn = ChainContract.withdrawAsset(
            sender = clRecipient,
            blockHash = blockHash,
            merkleProof = transferProofs,
            transferIndexInBlock = sendTxnLogIndex,
            amount = returnAmount,
            asset = Waves
          )

          waves1.api.broadcastAndWait(withdrawTxn)

          val balanceAfter = receiverBalance
          balanceAfter shouldBe (receiverBalanceBefore + returnAmount - withdrawTxn.fee.value)
        }
      }
    }

    "Dust returned" in {
      val elAmountWithDust = elAmount + 1

      val balanceBefore = terc20.getBalance(elSenderAddress)
      terc20E2CTransfer(elAmountWithDust, clAmount)
      val balanceAfter = terc20.getBalance(elSenderAddress)

      balanceAfter shouldBe balanceBefore - elAmount
    }
  }

  private def terc20E2CTransfer(elSendAmount: BigInt, clWithdrawAmount: Long): Unit = {
    val terc20 = ERC20.load(TErc20Address.hex, ec1.web3j, txnManager, new DefaultGasProvider)

    step("Send allowance")
    sendApproveErc20(terc20, elSendAmount.bigInteger)

    step("Initiate EL->CL transfer. Broadcast StandardBridge.sendBridgeErc20 transaction")
    val sendTxnReceipt = sendBridgeErc20(elSender, TErc20Address, elSendAmount)

    val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
    step(s"Block with transaction: $blockHash")

    val logsInBlock = ec1.engineApi
      .getLogs(blockHash, List(NativeBridgeAddress, StandardBridgeAddress), Nil)
      .explicitGet()
      .filter(_.topics.intersect(E2CTopics).nonEmpty)

    val sendTxnLogIndex = logsInBlock.indexWhere(_.transactionHash == sendTxnReceipt.getTransactionHash)
    sendTxnLogIndex shouldBe >=(0)

    val transferProofs = BridgeMerkleTree.mkTransferProofs(logsInBlock, sendTxnLogIndex).explicitGet().reverse

    step(s"Wait block $blockHash on contract")
    val blockConfirmationHeight = eventually {
      chainContract.getBlock(blockHash).value.height
    }

    step(s"Wait for block $blockHash ($blockConfirmationHeight) finalization")
    eventually {
      val currFinalizedHeight = chainContract.getFinalizedBlock.height
      step(s"Current finalized height: $currFinalizedHeight")
      currFinalizedHeight should be >= blockConfirmationHeight
    }

    def clRecipientBalance: Long = waves1.api.balance(clRecipient.toAddress, issueAsset)
    val recipientBalanceBefore   = clRecipientBalance

    step(
      s"Broadcast withdrawAsset transaction: transferIndexInBlock=$sendTxnLogIndex, amount=$clWithdrawAmount, " +
        s"merkleProof={${transferProofs.map(EthEncoding.toHexString).mkString(",")}}"
    )
    waves1.api.broadcastAndWait(
      ChainContract.withdrawAsset(
        sender = clRecipient,
        blockHash = blockHash,
        merkleProof = transferProofs,
        transferIndexInBlock = sendTxnLogIndex,
        amount = clWithdrawAmount,
        asset = issueAsset
      )
    )

    withClue("Assets received after EL->CL: ") {
      val balanceAfter = clRecipientBalance
      balanceAfter shouldBe (recipientBalanceBefore + clWithdrawAmount)
    }
  }

  private def getBalance(erc20Contract: ERC20, account: String): BigInt = erc20Contract.call_balanceOf(account).send()

  private def sendApproveErc20(erc20Contract: ERC20, ethAmount: BigInt): TransactionReceipt = {
    val txnResult = erc20Contract.send_approve(StandardBridgeAddress.toString, ethAmount.bigInteger).send()
    eventually {
      val r = ec1.web3j.ethGetTransactionReceipt(txnResult.getTransactionHash).send().getTransactionReceipt.toScala.value
      if (!r.isStatusOK) {
        fail(s"Expected successful send_approve, got: tx=${EvmEncoding.decodeRevertReason(r.getRevertReason)}")
      }
      r
    }
  }

  private def sendBridgeErc20(sender: Credentials, erc20Address: EthAddress, ethAmount: BigInt): TransactionReceipt = {
    val txnResult = standardBridge.sendBridgeErc20(sender, erc20Address, clRecipient.toAddress, ethAmount)

    // To overcome a failed block confirmation in a new epoch issue
    chainContract.waitForHeight(ec1.web3j.ethBlockNumber().send().getBlockNumber.longValueExact() + 2)

    waitFor(txnResult)
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
    waves1.api.waitForHeight(activationEpoch)

    step("Register asset")
    waves1.api.broadcastAndWait(ChainContract.issueAndRegister(TErc20Address, TErc20Decimals, "TERC20", "Test ERC20 token", issueAssetDecimals))
    eventually {
      // Because of possible rollbacks
      standardBridge.isRegistered(TErc20Address, ignoreExceptions = true) shouldBe true
    }
  }
}
