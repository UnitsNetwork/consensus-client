package units

import com.wavesplatform.*
import com.wavesplatform.account.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.crypto.Keccak256
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.state.{Height, IntegerDataEntry}
import com.wavesplatform.transaction.TxHelpers
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import play.api.libs.json.*
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.engine.model.Withdrawal.WithdrawalIndex
import units.client.engine.model.{EcBlock, Withdrawal}
import com.wavesplatform.transaction.Asset.IssuedAsset
import units.docker.EcContainer
import units.el.{DepositedTransaction, Erc20Client, StandardBridge}
import units.eth.{EmptyL2Block, EthAddress, EthereumConstants}
import units.util.{BlockToPayloadMapper, HexBytesConverter}
import units.{BlockHash, TestNetworkClient}
import java.math.BigInteger

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt

trait BaseBlockValidationSuite extends BaseDockerTestSuite {
  protected val setupMiner: SeedKeyPair              = miner11Account // Leaves after setting up the contracts
  protected val actingMiner: SeedKeyPair             = miner12Account
  protected val actingMinerRewardAddress: EthAddress = miner12RewardAddress

  // Note: additional miners are needed to avoid the actingMiner having majority of the stake
  protected val additionalMiner1: SeedKeyPair             = miner21Account
  protected val additionalMiner1RewardAddress: EthAddress = miner21RewardAddress
  protected val additionalMiner2: SeedKeyPair             = miner31Account
  protected val additionalMiner2RewardAddress: EthAddress = miner31RewardAddress

  protected val clSender: SeedKeyPair = clRichAccount1

  protected val elRecipient: EthAddress = elRichAddress1

  protected val userNativeTokenAmount       = 1
  protected val clNativeTokenAmount: Long   = UnitsConvert.toUnitsInWaves(userNativeTokenAmount)
  protected val elNativeTokenAmount: BigInt = UnitsConvert.toWei(userNativeTokenAmount)

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

  override def beforeAll(): Unit = {
    super.beforeAll()

    log.debug(s"setupMiner: ${setupMiner.toAddress}")
    log.debug(s"actingMiner: ${actingMiner.toAddress}")
    log.debug(s"additionalMiner1: ${additionalMiner1.toAddress}")
    log.debug(s"additionalMiner2: ${additionalMiner2.toAddress}")

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

    step(s"Wait actingMiner epoch")
    chainContract.waitForMinerEpoch(actingMiner)
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
      "127.0.0.1",
      waves1.networkPort,
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
      "127.0.0.1",
      waves1.networkPort,
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
      "127.0.0.1",
      waves1.networkPort,
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
}

class BlockValidationTestSuite4 extends BaseBlockValidationSuite {
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
      "127.0.0.1",
      waves1.networkPort,
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
}

class BlockValidationTestSuite5 extends BaseBlockValidationSuite {
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
      "127.0.0.1",
      waves1.networkPort,
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
}

class BlockValidationTestSuite6 extends BaseBlockValidationSuite {
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
      "127.0.0.1",
      waves1.networkPort,
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
}

class BlockValidationTestSuite7 extends BaseBlockValidationSuite {
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
      "127.0.0.1",
      waves1.networkPort,
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
}
