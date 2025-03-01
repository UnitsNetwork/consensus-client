package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.lang.v1.compiler.Terms.{CONST_BYTESTR, CONST_LONG, CONST_STRING}
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

import java.io.{File, FileInputStream}
import java.math.BigInteger
import scala.jdk.OptionConverters.RichOptional
import scala.sys.process.*
import scala.util.Using

class StandardBridgeTestSuite extends BaseDockerTestSuite {
  private val clAssetOwner = clRichAccount2
  private val clRecipient  = clRichAccount1
  private val elSender     = elRichAccount1

  private val issueAssetTxn   = TxHelpers.issue(clAssetOwner, decimals = 8)
  private val issueAsset      = IssuedAsset(issueAssetTxn.id())
  private val elAssetDecimals = 18

  private val userAmount = 1
  private val clAmount   = UnitsConvert.toWavesAtomic(userAmount, issueAssetTxn.decimals.value)
  private val elAmount   = UnitsConvert.toAtomic(userAmount, elAssetDecimals)

  private val testTransfers  = 2
  private val enoughClAmount = clAmount * testTransfers
  private val enoughElAmount = elAmount * testTransfers

  private val WWavesContractAddress = EthAddress.unsafeFrom("0x2e1f232a9439c3d459fceca0beef13acc8259dd8")
  private val TErc20Address         = EthAddress.unsafeFrom("0x9b8397f1b0fecd3a1a40cdd5e8221fa461898517")

  private val tenGwei = BigInt(Convert.toWei("10", Convert.Unit.GWEI).toBigIntegerExact)

  // TODO: Because we have to get a token from dictionary before amount checks. Fix with unit tests for contract
  "Negative" ignore {
    def test(amount: BigInt, expectedError: String): Unit = {
      val e = standardBridge.getRevertReasonForBridgeErc20(elSender, ???, clRecipient.toAddress, amount)
      e should include(expectedError)
    }

    "Amount should % 10 Gwei" in test(tenGwei + 1, "Sent value 10000000001 must be a multiple of 10000000000")

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
    def sendBridgeErc20(sender: Credentials, erc20Address: EthAddress, ethAmount: BigInt): TransactionReceipt = {
      val txnResult = standardBridge.sendBridgeErc20(sender, erc20Address, clRecipient.toAddress, ethAmount)

      // To overcome a failed block confirmation in a new epoch issue
      chainContract.waitForHeight(ec1.web3j.ethBlockNumber().send().getBlockNumber.longValueExact() + 2)

      eventually {
        val r = ec1.web3j.ethGetTransactionReceipt(txnResult.getTransactionHash).send().getTransactionReceipt.toScala.value
        if (!r.isStatusOK) fail(s"Expected successful sendBridgeErc20, got: tx=${EvmEncoding.decodeRevertReason(r.getRevertReason)}")
        r
      }
    }

    "Checking balances in EL->CL->EL transfers" in {
      step("Issue ERC20 token")
      val txManager      = new RawTransactionManager(ec1.web3j, elSender, EcContainer.ChainId, 10, 2000)
      val terc20         = TERC20.load("0x9b8397f1b0fecd3a1a40cdd5e8221fa461898517", ec1.web3j, txManager, new DefaultGasProvider)
      val terc20Decimals = terc20.call_decimals().send().intValueExact()

      log.info(s"Address: $TErc20Address")
      log.info(s"Balance of ${elSender.getAddress}: ${getBalance(TErc20Address, elSender.getAddress)}")

      registerAsset(issueAsset, TErc20Address, terc20Decimals)

      step("Send allowance")
      terc20.send_approve(standardBridgeAddress.toString, elAmount.bigInteger).send()

      step("Initiate EL->CL transfer. Broadcast StandardBridge.sendBridgeErc20 transaction")
      val sendTxnReceipt = sendBridgeErc20(elSender, TErc20Address, elAmount)

      val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
      step(s"Block with transaction: $blockHash")

      val logsInBlock = ec1.engineApi
        .getLogs(blockHash, List(nativeBridgeAddress, standardBridgeAddress), Nil)
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

      step(
        s"Broadcast withdrawAsset transaction: transferIndexInBlock=$sendTxnLogIndex, amount=$clAmount, " +
          s"merkleProof={${transferProofs.map(EthEncoding.toHexString).mkString(",")}}"
      )

      def clRecipientBalance: Long = waves1.api.balance(clRecipient.toAddress, issueAsset)

      val recipientBalanceBefore = clRecipientBalance

      waves1.api.broadcastAndWait(
        ChainContract.withdrawAsset(
          sender = clRecipient,
          blockHash = blockHash,
          merkleProof = transferProofs,
          transferIndexInBlock = sendTxnLogIndex,
          amount = clAmount,
          asset = issueAsset
        )
      )

      withClue("Assets recieved after EL->CL: ") {
        val balanceAfter = clRecipientBalance
        balanceAfter shouldBe (recipientBalanceBefore + clAmount)
      }

      step("Initiate CL->EL transfer. Broadcast ChainContract.transfer transaction")
      val elRecipientAddress = EthAddress.unsafeFrom("0xAAAA00000000000000000000000000000000AAAA")

      val returnUserAmount = BigDecimal(userAmount) / 2
      val returnClAmount   = UnitsConvert.toWavesAtomic(returnUserAmount, issueAssetTxn.decimals.value)
      val returnElAmount   = UnitsConvert.toAtomic(returnUserAmount, terc20Decimals)

      waves1.api.broadcastAndWait(ChainContract.transfer(clRecipient, elRecipientAddress, issueAsset, returnClAmount))
      withClue("Assets received: ") {
        eventually {
          getBalance(TErc20Address, elRecipientAddress.hex) shouldBe returnElAmount
          getBalance(TErc20Address, standardBridgeAddress.hex) shouldBe returnElAmount
        }
      }
    }

    "Check Waves transfer CL->EL->CL" in {
      withClue("4. Transfer Waves C>E>C") {
        val txManager = new RawTransactionManager(ec1.web3j, elRichAccount1, EcContainer.ChainId, 20, 2000)
        val wwaves    = WWaves.load(WWavesContractAddress.hex, ec1.web3j, txManager, new DefaultGasProvider)

        val transferUserAmount = 55
        // Same for EL and CL, because has same decimals
        val transferAmount = UnitsConvert.toWavesAtomic(transferUserAmount, UnitsConvert.WavesDecimals)

        val chainContractBalanceBefore = waves1.api.balance(chainContractAddress, Asset.Waves)

        def transferTxn: InvokeScriptTransaction = ChainContract.transfer(clRichAccount1, elRichAddress1, Asset.Waves, transferAmount)

        waves1.api.broadcastAndWait(transferTxn)
        waves1.api.balance(chainContractAddress, Asset.Waves) shouldBe chainContractBalanceBefore + transferAmount
        eventually {
          getBalance(WWavesContractAddress, elRichAddress1.hex) shouldBe BigInt(transferAmount)
          wwaves.call_totalSupply().send() shouldEqual BigInteger.valueOf(transferAmount)
        }

        val returnAmount = 32_00000000L
        step("Send allowance")
        wwaves.send_approve(standardBridgeAddress.toString, BigInteger.valueOf(returnAmount)).send()

        step("Broadcast StandardBridge.sendBridgeErc20 transaction")
        val sendTxnReceipt = sendBridgeErc20(elRichAccount1, WWavesContractAddress, returnAmount)

        val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
        step(s"Block with transaction: $blockHash")

        val logsInBlock = ec1.engineApi
          .getLogs(blockHash, List(nativeBridgeAddress, standardBridgeAddress), Nil)
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

  private def getBalance(erc20Address: EthAddress, account: String): BigInt = {
    val sender         = elSender
    val txnManager     = new RawTransactionManager(ec1.web3j, sender, EcContainer.ChainId)
    val gasProvider    = new DefaultGasProvider
    val standardBridge = TERC20.load(standardBridgeAddress.hex, ec1.web3j, sender, gasProvider) // TODO move to class?
    val funcCall       = standardBridge.call_balanceOf(account).encodeFunctionCall()

    val token = TERC20.load(erc20Address.hex, ec1.web3j, txnManager, gasProvider)
    token.call_balanceOf(account).send()
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Prepare: issue CL asset")
    waves1.api.broadcastAndWait(issueAssetTxn)

    step("Prepare: move assets and enable the asset in the registry")
    waves1.api.broadcast(TxHelpers.transfer(clAssetOwner, chainContractAddress, enoughClAmount, issueAsset))

    step("Prepare: wait for first block in EC")
    while (ec1.web3j.ethBlockNumber().send().getBlockNumber.compareTo(BigInteger.ONE) < 0) Thread.sleep(5000)

    val contractsDir = new File(sys.props("cc.it.contracts.dir"))
    Process(
      s"forge script -vvvv scripts/Deployer.s.sol:Deployer --private-key $elRichAccount1PrivateKey --fork-url http://localhost:${ec1.rpcPort} --broadcast",
      contractsDir,
      "CHAIN_ID" -> EcContainer.ChainId.toString
    ).!(ProcessLogger(out => log.info(out), err => log.error(err)))

    val contractAddresses = Using(new FileInputStream(new File(s"$contractsDir/target/deployments/${EcContainer.ChainId}/.deploy"))) { fis =>
      Json.parse(fis)
    }.get.as[Map[String, String]]

    waves1.api.broadcastAndWait(
      ChainContract.enableTokenTransfers(
        EthAddress.unsafeFrom(contractAddresses("StandardBridge")),
        EthAddress.unsafeFrom(contractAddresses("WWaves")),
        5
      )
    )
  }

  private def registerAsset(asset: Asset, erc20Address: EthAddress, elDecimals: Int): Unit =
    if (!standardBridge.isRegistered(erc20Address)) {
      step(s"Register asset: CL=$asset, EL=$erc20Address, elDecimals=$elDecimals")
      val txn = asset match {
        case asset: Asset.IssuedAsset => ChainContract.registerAsset(asset, erc20Address, elDecimals)
        case Asset.Waves              => ChainContract.registerWaves(erc20Address, chainContract.getAssetRegistrySize)
      }
      waves1.api.broadcastAndWait(txn)
      eventually {
        standardBridge.isRegistered(erc20Address) shouldBe true
      }
    }
}
