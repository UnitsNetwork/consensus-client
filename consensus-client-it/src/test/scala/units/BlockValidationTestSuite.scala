package units

import com.wavesplatform.*
import com.wavesplatform.account.*
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import org.web3j.protocol.core.DefaultBlockParameterName
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.engine.model.{EcBlock, Withdrawal}
import units.el.*
import units.eth.{EthAddress, Gwei}
import units.{BlockHash, TestNetworkClient}

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
