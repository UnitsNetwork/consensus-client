package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.{Asset, TxHelpers}
import com.wavesplatform.utils.EthEncoding
import org.web3j.crypto.Credentials
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.utils.Convert
import play.api.libs.json.Json
import units.bridge.{TERC20, WWaves}
import units.docker.EcContainer
import units.el.{BridgeMerkleTree, E2CTopics, EvmEncoding}
import units.eth.EthAddress
import units.test.TestEnvironment

import java.io.FileInputStream
import java.math.BigInteger
import scala.jdk.OptionConverters.RichOptional
import scala.sys.process.*
import scala.util.Using

class StandardBridgeTestSuite extends BaseDockerTestSuite {
  private val clAssetOwner    = clRichAccount2
  private val clRecipient     = clRichAccount1
  private val elSender        = elRichAccount1
  private val elSenderAddress = elRichAddress1

  private val issueAssetTxn   = TxHelpers.issue(clAssetOwner, decimals = 8)
  private val issueAsset      = IssuedAsset(issueAssetTxn.id())
  private val elAssetDecimals = 18

  private val userAmount = 1
  private val clAmount   = UnitsConvert.toWavesAtomic(userAmount, issueAssetTxn.decimals.value)
  private val elAmount   = UnitsConvert.toAtomic(userAmount, elAssetDecimals)

  private val testTransfers  = 2
  private val enoughClAmount = clAmount * testTransfers
  private val enoughElAmount = elAmount * testTransfers

  private val tenGwei = BigInt(Convert.toWei("10", Convert.Unit.GWEI).toBigIntegerExact)

  private val gasProvider     = new DefaultGasProvider
  private lazy val txnManager = new RawTransactionManager(ec1.web3j, elSender, EcContainer.ChainId, 20, 2000)
  private lazy val wwaves     = WWaves.load(WWavesAddress.hex, ec1.web3j, txnManager, gasProvider)
  private lazy val terc20     = TERC20.load(TErc20Address.hex, ec1.web3j, elSender, gasProvider)

  // TODO: Because we have to get a token from dictionary before amount checks. Fix with unit tests for contract
  "Negative" ignore {
    def test(amount: BigInt, expectedError: String): Unit = {
      val e = standardBridge.getRevertReasonForBridgeErc20(elSender, TErc20Address, clRecipient.toAddress, amount)
      e should include(expectedError)
    }

    "Amount should be between 10 and MAX_AMOUNT_IN_WEI Gwei" in {
      withClue("1. Less than 10 Gwei: ") {
        test(1, "Sent value 1 must be greater or equal to 10000000000")
      }

      withClue("2. More than MAX_AMOUNT_IN_WEI: ") {
        val maxAmountInWei = BigInt(Long.MaxValue) * tenGwei
        val biggerAmount   = (maxAmountInWei / tenGwei + 1) * tenGwei
        test(biggerAmount, s"Sent value $biggerAmount must be less or equal to $maxAmountInWei")
      }
    }

    "Can't transfer without registration" in test(elAmount, "Token is not registered")
  }

  "Positive" - {
    "Dust returned" in {
      val elAmountWithDust = elAmount + 1

      def elSenderBalance = BigInt(terc20.call_balanceOf(elSenderAddress.hex).send())
      val balanceBefore   = elSenderBalance
      terc20E2CTransfer(elAmountWithDust, clAmount)
      val balanceAfter = elSenderBalance

      balanceAfter shouldBe balanceBefore - elAmount
    }

    "Checking balances in EL->CL->EL transfers" in {
      terc20E2CTransfer(elAmount, clAmount)

      step("Initiate CL->EL transfer. Broadcast ChainContract.transfer transaction")
      val elRecipientAddress = EthAddress.unsafeFrom("0xAAAA00000000000000000000000000000000AAAA")

      val returnUserAmount = BigDecimal(userAmount) / 2
      val returnClAmount   = UnitsConvert.toWavesAtomic(returnUserAmount, issueAssetTxn.decimals.value)
      val returnElAmount   = UnitsConvert.toAtomic(returnUserAmount, TErc20Decimals)

      waves1.api.broadcastAndWait(ChainContract.transfer(clRecipient, elRecipientAddress, issueAsset, returnClAmount))
      withClue("Assets received: ") {
        eventually {
          getBalance(TErc20Address, elRecipientAddress.hex) shouldBe returnElAmount
          getBalance(TErc20Address, StandardBridgeAddress.hex) shouldBe returnElAmount
        }
      }
    }

    "Check Waves transfer CL->EL->CL" in {
      withClue("4. Transfer Waves C>E>C") {
        val transferUserAmount = 55
        // Same for EL and CL, because has same decimals
        val transferAmount = UnitsConvert.toWavesAtomic(transferUserAmount, UnitsConvert.WavesDecimals)

        val chainContractBalanceBefore = waves1.api.balance(chainContractAddress, Asset.Waves)

        def transferTxn: InvokeScriptTransaction = ChainContract.transfer(clRichAccount1, elSenderAddress, Asset.Waves, transferAmount)

        waves1.api.broadcastAndWait(transferTxn)
        waves1.api.balance(chainContractAddress, Asset.Waves) shouldBe chainContractBalanceBefore + transferAmount
        eventually {
          getBalance(WWavesAddress, elSenderAddress.hex) shouldBe BigInt(transferAmount)
          wwaves.call_totalSupply().send() shouldEqual BigInteger.valueOf(transferAmount)
        }

        val returnAmount = 32_00000000L

        step("Send allowance")
        sendApproveErc20(wwaves, returnAmount)

        step("Broadcast StandardBridge.sendBridgeErc20 transaction")
        val sendTxnReceipt = sendBridgeErc20(elSender, WWavesAddress, returnAmount)

        val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
        step(s"Block with transaction: $blockHash")

        val logsInBlock = ec1.engineApi
          .getLogs(blockHash, List(NativeBridgeAddress, StandardBridgeAddress), Nil)
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
  }

  private def terc20E2CTransfer(elSendAmount: BigInt, clWithdrawAmount: Long): Unit = {
    val terc20 = TERC20.load(TErc20Address.hex, ec1.web3j, txnManager, new DefaultGasProvider)

    registerAsset(issueAsset, TErc20Address, TErc20Decimals)

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

  private def getBalance(erc20Address: EthAddress, account: String): BigInt = {
    val funcCall = terc20.call_balanceOf(account).encodeFunctionCall()

    val token = TERC20.load(erc20Address.hex, ec1.web3j, txnManager, gasProvider)
    token.call_balanceOf(account).send()
  }

  private def sendApproveErc20(erc20Contract: TERC20 | WWaves, ethAmount: BigInt): TransactionReceipt = {
    val txnResult = erc20Contract match {
      case contract: TERC20 => contract.send_approve(StandardBridgeAddress.toString, ethAmount.bigInteger).send()
      case contract: WWaves => contract.send_approve(StandardBridgeAddress.toString, ethAmount.bigInteger).send()
    }

    eventually {
      val r = ec1.web3j.ethGetTransactionReceipt(txnResult.getTransactionHash).send().getTransactionReceipt.toScala.value
      if (!r.isStatusOK) {
//        val revertReason = erc20Contract match {
//          case contract: TERC20 =>
//            contract.call_approve(StandardBridgeAddress.toString, ethAmount.bigInteger).send()
//          case contract: WWaves =>
//            contract.call_approve(StandardBridgeAddress.toString, ethAmount.bigInteger).send()
//        }
        fail(s"Expected successful send_approve, got: tx=${EvmEncoding.decodeRevertReason(r.getRevertReason)}")
      }
      r
    }
  }

  private def sendBridgeErc20(sender: Credentials, erc20Address: EthAddress, ethAmount: BigInt): TransactionReceipt = {
    val txnResult = standardBridge.sendBridgeErc20(sender, erc20Address, clRecipient.toAddress, ethAmount)

    // To overcome a failed block confirmation in a new epoch issue
    chainContract.waitForHeight(ec1.web3j.ethBlockNumber().send().getBlockNumber.longValueExact() + 2)

    eventually {
      val r = ec1.web3j.ethGetTransactionReceipt(txnResult.getTransactionHash).send().getTransactionReceipt.toScala.value
      if (!r.isStatusOK) {
        val revertReason = standardBridge.getRevertReasonForBridgeErc20(sender, erc20Address, clRecipient.toAddress, ethAmount)
        fail(s"Expected successful sendBridgeErc20, got: tx=${EvmEncoding.decodeRevertReason(r.getRevertReason)}, revertReason=$revertReason")
      }
      r
    }
  }

  private def registerAsset(asset: IssuedAsset, erc20Address: EthAddress, elDecimals: Int): Unit =
    if (!standardBridge.isRegistered(erc20Address)) {
      step(s"Register asset: CL=$asset, EL=$erc20Address, elDecimals=$elDecimals")
      val txn = ChainContract.registerAsset(asset, erc20Address, elDecimals)
      waves1.api.broadcastAndWait(txn)
      eventually {
        standardBridge.isRegistered(erc20Address) shouldBe true
      }
    }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Prepare: issue CL asset")
    waves1.api.broadcastAndWait(issueAssetTxn)

    step("Prepare: move assets and enable the asset in the registry")
    waves1.api.broadcast(TxHelpers.transfer(clAssetOwner, chainContractAddress, enoughClAmount, issueAsset))

    step("Prepare: deploy contracts on EL")
    Process(
      s"forge script -vvvv scripts/Deployer.s.sol:Deployer --private-key $elRichAccount1PrivateKey --fork-url http://localhost:${ec1.rpcPort} --broadcast",
      TestEnvironment.ContractsDir,
      "CHAIN_ID" -> EcContainer.ChainId.toString
    ).!(ProcessLogger(out => log.info(out), err => log.error(err)))

    val contractAddresses = Using(new FileInputStream(TestEnvironment.ContractAddressesFile))(Json.parse).get.as[Map[String, String]]
    log.debug(s"Contract addresses: ${contractAddresses.mkString(", ")}")

    step("Enable token transfers")
    val activationEpoch = waves1.api.height() + 1
    waves1.api.broadcastAndWait(
      ChainContract.enableTokenTransfers(
        StandardBridgeAddress,
        WWavesAddress,
        activationEpoch = activationEpoch
      )
    )
    waves1.api.waitForHeight(activationEpoch)
  }
}
