package units

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.{ERC20Address, TxHelpers}
import com.wavesplatform.utils.EthEncoding
import org.web3j.crypto.{Credentials, RawTransaction}
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.{DefaultGasProvider, StaticGasProvider}
import org.web3j.utils.Convert
import units.bridge.{TERC20, WWaves}
import units.client.contract.HasConsensusLayerDappTxHelpers
import units.docker.EcContainer
import units.el.{BridgeMerkleTree, EvmEncoding}
import units.eth.EthAddress

import java.math.BigInteger
import scala.jdk.OptionConverters.RichOptional

// TODO: asset registered in EL/CL first cases, WAVES
class E2CStandardBridgeTestSuite extends BaseDockerTestSuite {
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

  private val WWavesContractAddress = EthAddress.unsafeFrom("0xa50a51c09a5c451c52bb714527e1974b686d8e77")

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
      val txManager = new RawTransactionManager(ec1.web3j, elSender, EcContainer.ChainId, 10, 2000)
      val terc20 = TERC20.load("0x42699a7612a82f1d9c36148af9c77354759b210b", ec1.web3j, txManager, new DefaultGasProvider)

      val erc20address = EthAddress.unsafeFrom(terc20.getContractAddress)
      log.info(s"Address: $erc20address")
      log.info(s"Balance of ${elSender.getAddress}: ${getBalance(erc20address.toString, elSender.getAddress)}")

      step("Register asset")
      waves1.api.broadcastAndWait(ChainContract.registerAsset(issueAsset, erc20address, 18))
      eventually {
        standardBridge.isRegistered(erc20address) shouldBe true
      }

      step("Send allowance")
      terc20.send_approve(standardBridgeAddress.toString, elAmount.bigInteger).send()

      step("Broadcast StandardBridge.sendBridgeErc20 transaction")
      val sendTxnReceipt = sendBridgeErc20(elSender, erc20address, elAmount)

      val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
      step(s"Block with transaction: $blockHash")

      val logsInBlock = ec1.engineApi.getLogs(blockHash, List(nativeBridgeAddress, standardBridgeAddress), Nil).explicitGet()
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

      withClue("3. Assets received: ") {
        step(
          s"Broadcast withdrawAsset transaction: transferIndexInBlock=$sendTxnLogIndex, amount=$clAmount, " +
            s"merkleProof={${transferProofs.map(EthEncoding.toHexString).mkString(",")}}"
        )

        def receiverBalance: Long = waves1.api.balance(clRecipient.toAddress, issueAsset)

        val receiverBalanceBefore = receiverBalance

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

        val balanceAfter = receiverBalance
        balanceAfter shouldBe (receiverBalanceBefore + clAmount)

        val elReceiverAddress = EthAddress.unsafeFrom("0xAAAA00000000000000000000000000000000AAAA")

        def transferTxn: InvokeScriptTransaction =
          ChainContract.transfer(
            clRecipient,
            elReceiverAddress,
            issueAsset,
            clAmount / 2,
            ChainContract.AssetTransferFunctionName
          )

        waves1.api.broadcastAndWait(transferTxn)
        eventually {
          val halfAmount = elAmount.bigInteger.divide(BigInteger.valueOf(2))
          getBalance(erc20address.hex, elReceiverAddress.hex) shouldBe halfAmount
          getBalance(erc20address.hex, standardBridgeAddress.hex) shouldBe halfAmount
        }
      }
    }

    "Check Waves transfer CL->EL->CL" in {
      withClue("4. Transfer Waves C>E>C") {
        val txManager = new RawTransactionManager(ec1.web3j, elRichAccount1, EcContainer.ChainId, 20, 2000)
        val wwaves = WWaves.load(WWavesContractAddress.hex, ec1.web3j, txManager, new DefaultGasProvider)

        waves1.api.broadcastAndWait(ChainContract.registerAsset(Asset.Waves, WWavesContractAddress, 8))
        eventually {
          standardBridge.isRegistered(WWavesContractAddress) shouldBe true
        }

        val transferAmount = 55_00000000L

        val chainContractBalanceBefore = waves1.api.balance(chainContractAddress, Asset.Waves)

        def transferTxn: InvokeScriptTransaction =
          ChainContract.transfer(
            clRichAccount1,
            elRichAddress1,
            Asset.Waves,
            transferAmount,
            ChainContract.AssetTransferFunctionName
          )

        waves1.api.broadcastAndWait(transferTxn)
        waves1.api.balance(chainContractAddress, Asset.Waves) shouldBe chainContractBalanceBefore + transferAmount

        eventually {
          getBalance(wwaves.getContractAddress, elRichAddress1.hex) shouldBe BigInteger.valueOf(transferAmount)
          wwaves.call_totalSupply().send() shouldBe BigInteger.valueOf(transferAmount)
        }

        val returnAmount = 32_00000000L
        step("Send allowance")
        wwaves.send_approve(standardBridgeAddress.toString, BigInteger.valueOf(returnAmount)).send()

        step("Broadcast StandardBridge.sendBridgeErc20 transaction")
        val sendTxnReceipt = sendBridgeErc20(elRichAccount1, WWavesContractAddress, returnAmount)

        val blockHash = BlockHash(sendTxnReceipt.getBlockHash)
        step(s"Block with transaction: $blockHash")

        val logsInBlock = ec1.engineApi.getLogs(blockHash, List(nativeBridgeAddress, standardBridgeAddress), Nil).explicitGet()
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

          def receiverBalance: Long = waves1.api.balance(clRichAccount2.toAddress, Asset.Waves)

          val receiverBalanceBefore = receiverBalance

          waves1.api.broadcastAndWait(
            ChainContract.withdrawAsset(
              sender = clRichAccount2,
              blockHash = blockHash,
              merkleProof = transferProofs,
              transferIndexInBlock = sendTxnLogIndex,
              amount = returnAmount,
              asset = Waves
            )
          )

          val balanceAfter = receiverBalance
          balanceAfter shouldBe (receiverBalanceBefore + returnAmount - 500000L)

        }
      }
    }
  }

  private def getBalance(contractAddress: String, account: String) = {
    val sender: Credentials = elSender
    val txnManager          = new RawTransactionManager(ec1.web3j, sender, EcContainer.ChainId)
    val gasProvider         = new DefaultGasProvider
    val contract            = TERC20.load(standardBridgeAddress.hex, ec1.web3j, sender, gasProvider) // TODO move to class?
    val funcCall            = contract.call_balanceOf(account).encodeFunctionCall()

    val c = TERC20.load(contractAddress, ec1.web3j, txnManager, gasProvider)
    c.call_balanceOf(account).send()
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    step("Prepare: issue CL asset")
    waves1.api.broadcastAndWait(issueAssetTxn)

    step("Prepare: move assets and enable the asset in the registry")
    waves1.api.broadcast(TxHelpers.transfer(clAssetOwner, chainContractAddress, enoughClAmount, issueAsset))

    step("Prepare: wait for first block in EC")
    while (ec1.web3j.ethBlockNumber().send().getBlockNumber.compareTo(BigInteger.ONE) < 0) Thread.sleep(5000)

    import scala.sys.process.*

    ProcessLogger
    (s"forge script -vvvv --config-path ${sys.props("cc.it.contracts.dir")}/foundry.toml ${sys.props("cc.it.contracts.dir")}/scripts/Deployer.s.sol:Deployer " +
      s"--fork-url http://localhost:${ec1.rpcPort} " +
      s"--broadcast --private-key 8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63").!(ProcessLogger(out => log.info(out), err => log.error(err))).shouldBe(0)
  }
}
