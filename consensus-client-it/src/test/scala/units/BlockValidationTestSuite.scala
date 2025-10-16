package units

import com.wavesplatform.*
import com.wavesplatform.account.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.crypto.Keccak256
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.state.{Height, IntegerDataEntry}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.{Asset, TxHelpers}
import monix.execution.atomic.AtomicInt
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import play.api.libs.json.*
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.engine.model.Withdrawal.WithdrawalIndex
import units.client.engine.model.{EcBlock, Withdrawal}
import units.docker.EcContainer
import units.el.*
import units.eth.{EmptyL2Block, EthAddress, EthereumConstants, Gwei}
import units.util.{BlockToPayloadMapper, HexBytesConverter}
import units.{BlockHash, TestNetworkClient}

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

  private def calculateRandao(hitSource: ByteStr, parentHash: BlockHash): String = {
    val msg = hitSource.arr ++ HexBytesConverter.toBytes(parentHash)
    HexBytesConverter.toHex(crypto.secureHash(msg))
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
    val prevRandao         = calculateRandao(hitSource, elParentBlock.hash)

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

class BlockValidationTestSuite1 extends BaseBlockValidationSuite {
  "Valid block: native token, correct transfer" in {
    val ethBalanceBefore       = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeETHTransaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
        to = elRecipient,
        amount = clNativeTokenAmount.longValue
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        chainContract.nativeTokenId,
        clNativeTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Deposited transaction changes balances")
    val ethBalanceAfter = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    ethBalanceBefore.longValue shouldBe ethBalanceAfter.longValue - elNativeTokenAmount.longValue

    step("Assertion: EL height grows")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe (elParentBlock.height.longValue + 1)
  }

  override def beforeAll(): Unit = setupForNativeTokenTransfer()
}

class BlockValidationTestSuite2 extends BaseBlockValidationSuite {
  "Valid block: no transfers" in {
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val depositedTransactions = Vector.empty

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    // Note: No transfers on the chain contract in this test case

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: EL height grows")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe (elParentBlock.height.longValue + 1)
  }

  override def beforeAll(): Unit = setupForNativeTokenTransfer()
}

class BlockValidationTestSuite3 extends BaseBlockValidationSuite {
  "Invalid block: unexpected deposited transaction" in {
    val ethBalanceBefore       = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeETHTransaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
        to = elRecipient,
        amount = clNativeTokenAmount.longValue
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    // Note: No transfers on the chain contract in this test case

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Unexpected deposited transaction doesn't affect balances")
    val ethBalanceAfter = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    ethBalanceBefore shouldBe ethBalanceAfter

    step("Assertion: While the block exists on EC1, the height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForNativeTokenTransfer()
}

class BlockValidationTestSuite4 extends BaseBlockValidationSuite {
  "Invalid block: native token, missing deposited transaction" in {
    val ethBalanceBefore       = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val depositedTransactions = Vector()

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        chainContract.nativeTokenId,
        clNativeTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Doesn't affect balances")
    val ethBalanceAfter = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    ethBalanceBefore shouldBe ethBalanceAfter

    step("Assertion: While the block exists on EC1, the height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForNativeTokenTransfer()
}

class BlockValidationTestSuite5 extends BaseBlockValidationSuite {
  "Invalid block: native token, invalid amount" in {
    val ethBalanceBefore       = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val invalidAmount = clNativeTokenAmount.longValue - 1L

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeETHTransaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
        to = elRecipient,
        amount = invalidAmount
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        chainContract.nativeTokenId,
        clNativeTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Unexpected deposited transaction doesn't affect balances")
    val ethBalanceAfter = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    ethBalanceBefore shouldBe ethBalanceAfter

    step("Assertion: While the block exists on EC1, the height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForNativeTokenTransfer()
}

class BlockValidationTestSuite6 extends BaseBlockValidationSuite {
  "Invalid block: native token, invalid recipient" in {
    val invalidRecipient = additionalMiner1RewardAddress

    val ethBalanceBefore       = ec1.web3j.ethGetBalance(invalidRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeETHTransaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
        to = invalidRecipient,
        amount = clNativeTokenAmount.longValue
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient, // Valid recipient, while the deposited transaction has invalid one
        chainContract.nativeTokenId,
        clNativeTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Unexpected deposited transaction doesn't affect balances")
    val ethBalanceAfter = ec1.web3j.ethGetBalance(invalidRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    ethBalanceBefore shouldBe ethBalanceAfter

    step("Assertion: While the block exists on EC1, the height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForNativeTokenTransfer()
}

class BlockValidationTestSuite7 extends BaseBlockValidationSuite {
  "Invalid block: native token, invalid sender" in {
    val invalidSender = additionalMiner1RewardAddress

    val ethBalanceBefore       = ec1.web3j.ethGetBalance(invalidSender.toString, DefaultBlockParameterName.LATEST).send().getBalance
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeETHTransaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        from = invalidSender,
        to = elRecipient,
        amount = clNativeTokenAmount.longValue
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        chainContract.nativeTokenId,
        clNativeTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Unexpected deposited transaction doesn't affect balances")
    val ethBalanceAfter = ec1.web3j.ethGetBalance(invalidSender.toString, DefaultBlockParameterName.LATEST).send().getBalance
    ethBalanceBefore shouldBe ethBalanceAfter

    step("Assertion: While the block exists on EC1, the height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForNativeTokenTransfer()
}

class BlockValidationTestSuite8 extends BaseBlockValidationSuite {
  "Invalid block: native token, invalid standard bridge address" in {
    val ethBalanceBefore       = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val invalidStandardBridgeAddress = additionalMiner1RewardAddress

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeETHTransaction(
        transferIndex = 0L,
        standardBridgeAddress = invalidStandardBridgeAddress,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
        to = elRecipient,
        amount = clNativeTokenAmount.longValue
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        chainContract.nativeTokenId,
        clNativeTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Unexpected deposited transaction doesn't affect balances")
    val ethBalanceAfter = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    ethBalanceBefore shouldBe ethBalanceAfter

    step("Assertion: While the block exists on EC1, the height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForNativeTokenTransfer()
}

class BlockValidationTestSuite9 extends BaseBlockValidationSuite {
  "Invalid block: native token, unexpected extra withdrawal" in {
    val ethBalanceBefore       = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val rewardWithdrawal     = mkRewardWithdrawal(elParentBlock)
    val unexpectedWithdrawal = Withdrawal(rewardWithdrawal.index + 1, elRecipient, Gwei.ofRawGwei(3_000_000_000L))
    val withdrawals          = Vector(rewardWithdrawal, unexpectedWithdrawal)

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeETHTransaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
        to = elRecipient,
        amount = clNativeTokenAmount.longValue
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        chainContract.nativeTokenId,
        clNativeTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Deposited transaction doesn't affect balances")
    val ethBalanceAfter = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    ethBalanceBefore shouldBe ethBalanceAfter

    step("Assertion: While the block exists on EC1, the height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForNativeTokenTransfer()
}

class BlockValidationTestSuite10 extends BaseBlockValidationSuite {
  "Valid block: asset token, correct transfer" in {
    val balanceBefore          = terc20.getBalance(elRecipient)
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeErc20Transaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        token = TErc20Address,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
        to = elRecipient,
        amount = EAmount(elAssetTokenAmount.bigInteger)
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        issueAsset,
        clAssetTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Deposited transaction changes balances")
    val balanceAfter = terc20.getBalance(elRecipient)
    balanceAfter.longValue shouldBe (balanceBefore.longValue + elAssetTokenAmount.longValue)

    step("Assertion: EL height grows")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe (elParentBlock.height.longValue + 1)
  }

  override def beforeAll(): Unit = setupForAssetTokenTransfer()
}

class BlockValidationTestSuite11 extends BaseBlockValidationSuite {
  "Invalid block: asset token, invalid standardBridgeAddress" in {
    val balanceBefore          = terc20.getBalance(elRecipient)
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val invalidStandardBridgeAddress = additionalMiner1RewardAddress

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeErc20Transaction(
        transferIndex = 0L,
        standardBridgeAddress = invalidStandardBridgeAddress,
        token = TErc20Address,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
        to = elRecipient,
        amount = EAmount(elAssetTokenAmount.bigInteger)
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        issueAsset,
        clAssetTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Deposited transaction doesn't change balance")
    val balanceAfter = terc20.getBalance(elRecipient)
    balanceAfter.longValue shouldBe balanceBefore.longValue

    step("Assertion: EL height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForAssetTokenTransfer()
}

class BlockValidationTestSuite12 extends BaseBlockValidationSuite {
  "Invalid block: asset token, invalid token address" in {
    val balanceBefore          = terc20.getBalance(elRecipient)
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val invalidTokenAddress = WWavesAddress

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeErc20Transaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        token = invalidTokenAddress,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
        to = elRecipient,
        amount = EAmount(elAssetTokenAmount.bigInteger)
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        issueAsset,
        clAssetTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Deposited transaction doesn't change balance")
    val balanceAfter = terc20.getBalance(elRecipient)
    balanceAfter.longValue shouldBe balanceBefore.longValue

    step("Assertion: EL height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForAssetTokenTransfer()
}

class BlockValidationTestSuite13 extends BaseBlockValidationSuite {
  "Invalid block: asset token, invalid sender address" in {
    val balanceBefore          = terc20.getBalance(elRecipient)
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val invalidSenderAddress = additionalMiner1RewardAddress

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeErc20Transaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        token = TErc20Address,
        from = invalidSenderAddress,
        to = elRecipient,
        amount = EAmount(elAssetTokenAmount.bigInteger)
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        issueAsset,
        clAssetTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Deposited transaction doesn't change balance")
    val balanceAfter = terc20.getBalance(elRecipient)
    balanceAfter.longValue shouldBe balanceBefore.longValue

    step("Assertion: EL height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForAssetTokenTransfer()
}

class BlockValidationTestSuite14 extends BaseBlockValidationSuite {
  "Invalid block: asset token, invalid recipient address" in {
    val balanceBefore          = terc20.getBalance(elRecipient)
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val invalidRecipientAddress = additionalMiner1RewardAddress

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeErc20Transaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        token = TErc20Address,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
        to = invalidRecipientAddress,
        amount = EAmount(elAssetTokenAmount.bigInteger)
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        issueAsset,
        clAssetTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Deposited transaction doesn't change balance")
    val balanceAfter = terc20.getBalance(elRecipient)
    balanceAfter.longValue shouldBe balanceBefore.longValue

    step("Assertion: EL height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForAssetTokenTransfer()
}

class BlockValidationTestSuite15 extends BaseBlockValidationSuite {
  "Invalid block: asset token, invalid amount" in {
    val balanceBefore          = terc20.getBalance(elRecipient)
    val elParentBlock: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    val withdrawals = Vector(mkRewardWithdrawal(elParentBlock))

    val invalidAmount = EAmount((elAssetTokenAmount - 1).bigInteger)

    val depositedTransactions = Vector(
      StandardBridge.mkFinalizeBridgeErc20Transaction(
        transferIndex = 0L,
        standardBridgeAddress = StandardBridgeAddress,
        token = TErc20Address,
        from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
        to = elRecipient,
        amount = invalidAmount
      )
    )

    val (payload, simulatedBlockHash, hitSource) = mkSimulatedBlock(elParentBlock, withdrawals, depositedTransactions)

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        issueAsset,
        clAssetTokenAmount
      )
    )

    step("Register the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elParentBlock.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Send the simulated block to waves1")
    TestNetworkClient.send(
      waves1,
      chainContractAddress,
      NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    )

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Deposited transaction doesn't change balance")
    val balanceAfter = terc20.getBalance(elRecipient)
    balanceAfter.longValue shouldBe balanceBefore.longValue

    step("Assertion: EL height doesn't grow")
    val elBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    elBlockAfter.height.longValue shouldBe elParentBlock.height.longValue
  }

  override def beforeAll(): Unit = setupForAssetTokenTransfer()
}
