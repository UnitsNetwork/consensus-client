package units

import com.wavesplatform.*
import com.wavesplatform.account.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.crypto.Keccak256
import com.wavesplatform.state.{Height, IntegerDataEntry}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.{Asset, TxHelpers}
import monix.execution.atomic.AtomicInt
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import play.api.libs.json.*
import units.BlockHash
import units.client.engine.model.Withdrawal.WithdrawalIndex
import units.client.engine.model.{EcBlock, Withdrawal}
import units.docker.EcContainer
import units.ELUpdater
import units.el.*
import units.eth.{EmptyL2Block, EthAddress, EthereumConstants}
import units.util.{BlockToPayloadMapper, HexBytesConverter}

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.jdk.OptionConverters.RichOptional

trait BaseBlockValidationSuite extends BaseDockerTestSuite {
  protected val setupMiner: SeedKeyPair              = miner11Account // Leaves after setting up the contracts
  protected val actingMiner: SeedKeyPair             = miner12Account
  protected val actingMinerRewardAddress: EthAddress = miner12RewardAddress

  // Note: additional miners are needed to avoid the actingMiner having majority of the stake
  protected val additionalMiner1: SeedKeyPair             = miner21Account
  protected val additionalMiner1RewardAddress: EthAddress = miner21RewardAddress
  protected val additionalMiner2: SeedKeyPair             = miner31Account
  protected val additionalMiner2RewardAddress: EthAddress = miner31RewardAddress

  // transfers
  protected val clSender: SeedKeyPair   = clRichAccount1
  protected val elRecipient: EthAddress = elRichAddress1

  // native transfers
  protected val userNativeTokenAmount       = 1
  protected val clNativeTokenAmount: Long   = UnitsConvert.toUnitsInWaves(userNativeTokenAmount)
  protected val elNativeTokenAmount: BigInt = UnitsConvert.toWei(userNativeTokenAmount)

  // asset transfers
  protected val gasProvider              = new DefaultGasProvider
  protected lazy val txnManager          = new RawTransactionManager(ec1.web3j, elRichAccount1, EcContainer.ChainId, 20, 2000)
  protected lazy val terc20              = new Erc20Client(ec1.web3j, TErc20Address, txnManager, gasProvider)
  protected val issueAssetDecimals: Byte = 8.toByte
  protected lazy val issueAsset: IssuedAsset = chainContract.getRegisteredAsset(1) match {
    case ia: IssuedAsset => ia
    case _               => fail("Expected issued asset")
  }
  protected val userAssetTokenAmount       = 1
  protected val clAssetTokenAmount: Long   = UnitsConvert.toWavesAtomic(userAssetTokenAmount, issueAssetDecimals)
  protected val elAssetTokenAmount: BigInt = UnitsConvert.toAtomic(userAssetTokenAmount, TErc20Decimals)

  private def correctedTime(): Long = {
    val ntpTimestamp = System.currentTimeMillis()
    val nanoTime     = System.nanoTime()
    val timestamp    = ntpTimestamp
    val offset       = (System.nanoTime() - nanoTime) / 1000000
    timestamp + offset
  }

  @tailrec
  private def getLastWithdrawalIndex(hash: BlockHash): JobResult[WithdrawalIndex] =
    ec1.engineApi.getBlockByHash(hash) match {
      case Left(e)     => Left(e)
      case Right(None) => Left(ClientError(s"Can't find $hash block on EC during withdrawal search"))
      case Right(Some(ecBlock)) =>
        ecBlock.withdrawals.lastOption match {
          case Some(lastWithdrawal) => Right(lastWithdrawal.index)
          case None =>
            if (ecBlock.height == 0) Right(-1L)
            else getLastWithdrawalIndex(ecBlock.parentHash)
        }
    }

  protected final def mkRewardWithdrawal(elParentBlock: EcBlock): Withdrawal = {
    val chainContractOptions = chainContract.getOptions

    val elWithdrawalIndexBefore = (elParentBlock.withdrawals.lastOption.map(_.index) match {
      case Some(r) => Right(r)
      case None =>
        if (elParentBlock.height - 1 <= EthereumConstants.GenesisBlockHeight) Right(-1L)
        else getLastWithdrawalIndex(elParentBlock.parentHash)
    }).explicitGet()
    Withdrawal(elWithdrawalIndexBefore + 1, elParentBlock.minerRewardL2Address, chainContractOptions.miningReward)
  }

  protected final def mkSimulatedBlock(
      elParentBlock: EcBlock,
      withdrawals: Seq[Withdrawal],
      depositedTransactions: Seq[DepositedTransaction]
  ): (JsObject, String, ByteStr) = {

    step("Building a simulated block")
    val feeRecipient = actingMinerRewardAddress

    val currentUnixTs   = correctedTime() / 1000
    val blockDelay      = 6
    val nextBlockUnixTs = (elParentBlock.timestamp + blockDelay).max(currentUnixTs)

    val currentEpochHeader = waves1.api.blockHeader(waves1.api.height()).value
    val hitSource          = ByteStr.decodeBase58(currentEpochHeader.VRF).get
    val prevRandao         = ELUpdater.calculateRandao(hitSource, elParentBlock.hash)

    val txHashes = depositedTransactions.map(t => HexBytesConverter.toHex(Keccak256.hash(HexBytesConverter.toBytes(t.toHex)))).mkString(", ")
    log.debug(s"Deposited transactions hashes: $txHashes")

    val simulatedBlock: JsObject = ec1.engineApi
      .simulate(
        EmptyL2Block.mkSimulateCall(elParentBlock, feeRecipient, nextBlockUnixTs, prevRandao, withdrawals, depositedTransactions),
        elParentBlock.hash
      )
      .explicitGet()
      .head

    val payload = BlockToPayloadMapper.toPayloadJson(
      simulatedBlock,
      Json.obj(
        "transactions" -> depositedTransactions.map(_.toHex),
        "withdrawals"  -> Json.toJson(withdrawals)
      )
    )

    val simulatedBlockHash: String = (simulatedBlock \ "hash").as[String]

    (payload, simulatedBlockHash, hitSource)
  }

  protected def deployContractsAndActivateTransferFeatures(): Unit = {
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
  }

  protected def transferNativeTokenToClSender(): Unit = {
    step("Prepare: issue tokens on chain contract and transfer to a user")
    waves1.api.broadcastAndWait(
      TxHelpers.reissue(
        asset = chainContract.nativeTokenId,
        sender = chainContractAccount,
        amount = clNativeTokenAmount
      )
    )
    waves1.api.broadcastAndWait(
      TxHelpers.transfer(
        from = chainContractAccount,
        to = clSender.toAddress,
        amount = clNativeTokenAmount,
        asset = chainContract.nativeTokenId
      )
    )
  }

  protected def transferAssetTokenToClSender(): Unit = {
    step("Register asset")
    waves1.api.broadcastAndWait(ChainContract.issueAndRegister(TErc20Address, TErc20Decimals, "TERC20", "Test ERC20 token", issueAssetDecimals))

    eventually {
      standardBridge.isRegistered(TErc20Address, ignoreExceptions = true) shouldBe true
    }

    step("Transfer asset from EL to CL")

    val currNonce =
      AtomicInt(ec1.web3j.ethGetTransactionCount(elRichAddress1.hex, DefaultBlockParameterName.PENDING).send().getTransactionCount.intValueExact())
    def nextNonce: Int = currNonce.getAndIncrement()

    waitFor(terc20.sendApprove(StandardBridgeAddress, elAssetTokenAmount, nextNonce))

    val e2cIssuedTxn = standardBridge.sendBridgeErc20(elRichAccount1, TErc20Address, clSender.toAddress, elAssetTokenAmount, nextNonce)

    chainContract.waitForEpoch(waves1.api.height() + 1)
    val e2cReceipt =
      eventually {
        val hash = e2cIssuedTxn.getTransactionHash
        withClue(s"$hash: ") {
          ec1.web3j.ethGetTransactionReceipt(hash).send().getTransactionReceipt.toScala.value
        }
      }

    val e2cBlockHash = BlockHash(e2cReceipt.getBlockHash)

    val e2cLogsInBlock = ec1.engineApi
      .getLogs(e2cBlockHash, List(NativeBridgeAddress, StandardBridgeAddress), Nil)
      .explicitGet()
      .filter(_.topics.intersect(E2CTopics).nonEmpty)

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
    waves1.api.broadcastAndWait(
      ChainContract.withdrawAsset(
        sender = clSender,
        blockHash = e2cBlockHash,
        merkleProof = BridgeMerkleTree.mkTransferProofs(e2cLogsInBlock, 0).explicitGet().reverse,
        transferIndexInBlock = 0,
        amount = UnitsConvert.toWavesAtomic(userAssetTokenAmount, issueAssetDecimals),
        asset = issueAsset
      )
    )
  }

  protected def leaveSetupMinerAndJoinOthers(): Unit = {
    log.debug(s"setupMiner: ${setupMiner.toAddress}")
    log.debug(s"actingMiner: ${actingMiner.toAddress}")
    log.debug(s"additionalMiner1: ${additionalMiner1.toAddress}")
    log.debug(s"additionalMiner2: ${additionalMiner2.toAddress}")

    step(s"additionalMiner1 join")
    waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = additionalMiner1,
        elRewardAddress = additionalMiner1RewardAddress
      )
    )

    step(s"Wait additionalMiner1 epoch")
    chainContract.waitForMinerEpoch(additionalMiner1)

    step(s"setupMiner leave")
    eventually(interval(500 millis)) {
      waves1.api.broadcastAndWait(ChainContract.leave(setupMiner))
    }

    step(s"additionalMiner2 join")
    waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = additionalMiner2,
        elRewardAddress = additionalMiner2RewardAddress
      )
    )

    step(s"actingMiner join")
    waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = actingMiner,
        elRewardAddress = actingMinerRewardAddress
      )
    )

    step(s"Wait actingMiner epoch")
    chainContract.waitForMinerEpoch(actingMiner)
  }

  protected def setupForNativeTokenTransfer(): Unit = {
    super.beforeAll()
    deployContractsAndActivateTransferFeatures()
    transferNativeTokenToClSender()
    leaveSetupMinerAndJoinOthers()
  }

  protected def setupForAssetTokenTransfer(): Unit = {
    super.beforeAll()
    deployContractsAndActivateTransferFeatures()
    transferAssetTokenToClSender()
    leaveSetupMinerAndJoinOthers()
  }
}
