package units

import com.wavesplatform.*
import com.wavesplatform.account.*
import com.wavesplatform.api.LoggingBackend
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.crypto.Keccak256
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.state.{Height, IntegerDataEntry}
import com.wavesplatform.transaction.TxHelpers
import org.web3j.protocol.core.DefaultBlockParameterName
import play.api.libs.json.*
import sttp.client3.*
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.engine.model.Withdrawal.WithdrawalIndex
import units.client.engine.model.{EcBlock, Withdrawal}
import units.docker.{Networks, WavesNodeContainer}
import units.el.{DepositedTransaction, StandardBridge}
import units.eth.{EmptyL2Block, EthAddress, EthereumConstants}
import units.util.{BlockToPayloadMapper, HexBytesConverter}
import units.{BlockHash, TestNetworkClient}

import scala.annotation.tailrec

class BlockValidationTestSuite0 extends BaseDockerTestSuite {

  private val setupMiner               = miner11Account // Leaves after setting up the contracts
  private val actingMiner              = miner12Account
  private val actingMinerRewardAddress = miner12RewardAddress

  // Note: additional miners are needed to avoid the actingMiner having majority of the stake
  private val additionalMiner1              = miner21Account
  private val additionalMiner1RewardAddress = miner21RewardAddress
  private val additionalMiner2              = miner31Account
  private val additionalMiner2RewardAddress = miner31RewardAddress

  private val clSender    = clRichAccount1
  private val elRecipient = elRichAddress1

  private val userAmount = 1
  private val clAmount   = UnitsConvert.toUnitsInWaves(userAmount)
  private val elAmount   = UnitsConvert.toWei(userAmount)

  private implicit val httpClientBackend: SttpBackend[Identity, Any] = new LoggingBackend(HttpClientSyncBackend())
  override lazy val waves1: WavesNodeContainer = new WavesNodeContainer(
    network = network,
    number = 1,
    ip = Networks.ipForNode(3),
    baseSeed = "devnet-1",
    chainContractAddress = chainContractAddress,
    ecEngineApiUrl = ec1.engineApiDockerUrl,
    genesisConfigPath = wavesGenesisConfigPath,
    enableMining = true
  )

  log.debug(s"setupMiner: ${setupMiner.toAddress}")
  log.debug(s"actingMiner: ${actingMiner.toAddress}")
  log.debug(s"additionalMiner1: ${additionalMiner1.toAddress}")
  log.debug(s"additionalMiner2: ${additionalMiner2.toAddress}")

  "Native token: Successful transfer" in {
    step(s"additionalMiner1 join")
    waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = additionalMiner1,
        elRewardAddress = additionalMiner1RewardAddress
      )
    )

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

    step(s"Wait additionalMiner1 epoch")
    chainContract.waitForMinerEpoch(additionalMiner2)

    step(s"EL setupMiner leave")
    waves1.api.broadcastAndWait(ChainContract.leave(setupMiner))

    val ethBalanceBefore = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance

    step(s"Wait actingMiner epoch")
    chainContract.waitForMinerEpoch(actingMiner)

    step("Getting last EL block before")
    val ecBlockBefore: EcBlock = ec1.engineApi.getLastExecutionBlock().explicitGet()

    step("Building a simulated block")
    val feeRecipient = actingMinerRewardAddress

    val ntpTimestamp = System.currentTimeMillis()
    val nanoTime     = System.nanoTime()
    def correctedTime(): Long = {
      val timestamp = ntpTimestamp
      val offset    = (System.nanoTime() - nanoTime) / 1000000
      timestamp + offset
    }
    val currentUnixTs   = correctedTime() / 1000
    val blockDelay      = 6
    val nextBlockUnixTs = (ecBlockBefore.timestamp + blockDelay).max(currentUnixTs)

    def calculateRandao(hitSource: ByteStr, parentHash: BlockHash): String = {
      val msg = hitSource.arr ++ HexBytesConverter.toBytes(parentHash)
      HexBytesConverter.toHex(crypto.secureHash(msg))
    }
    val currentEpochHeader = waves1.api.blockHeader(waves1.api.height()).value
    val hitSource          = ByteStr.decodeBase58(currentEpochHeader.VRF).get
    val prevRandao         = calculateRandao(hitSource, ecBlockBefore.hash)

    val chainContractOptions = chainContract.getOptions

    val elWithdrawalIndexBefore = (ecBlockBefore.withdrawals.lastOption.map(_.index) match {
      case Some(r) => Right(r)
      case None =>
        if (ecBlockBefore.height - 1 <= EthereumConstants.GenesisBlockHeight) Right(-1L)
        else getLastWithdrawalIndex(ecBlockBefore.parentHash)
    }).explicitGet()
    val withdrawalIndex = elWithdrawalIndexBefore + 1
    val rewardAddress   = ecBlockBefore.minerRewardL2Address
    val withdrawals     = Vector(Withdrawal(withdrawalIndex, rewardAddress, chainContractOptions.miningReward))

    val depositedTransaction = StandardBridge.mkFinalizeBridgeETHTransaction(
      transferIndex = 0L,
      standardBridgeAddress = StandardBridgeAddress,
      from = EthAddress.unsafeFrom(clSender.toAddress.bytes.drop(2).take(20)),
      to = elRecipient,
      amount = clAmount.longValue
    )

    val txHash = HexBytesConverter.toHex(Keccak256.hash(HexBytesConverter.toBytes(depositedTransaction.toHex)))
    log.debug(s"txHash: $txHash")

    val depositedTransactions: Seq[DepositedTransaction] = Vector(depositedTransaction)

    val simulatedBlock: JsObject = ec1.engineApi
      .simulate(
        EmptyL2Block.mkSimulateCall(ecBlockBefore, feeRecipient, nextBlockUnixTs, prevRandao, withdrawals, depositedTransactions),
        ecBlockBefore.hash
      )
      .explicitGet()
      .head

    val simulatedBlockHash = (simulatedBlock \ "hash").as[String]

    step("Transfer on the chain contract")
    waves1.api.broadcastAndWait(
      ChainContract.transfer(
        clSender,
        elRecipient,
        chainContract.nativeTokenId,
        clAmount
      )
    )

    step("Registering the simulated block on the chain contract")
    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = actingMiner,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(ecBlockBefore.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Sending the simulated block to waves1")
    val payload = BlockToPayloadMapper.toPayloadJson(
      simulatedBlock,
      Json.obj(
        "transactions" -> depositedTransactions.map(_.toHex),
        "withdrawals"  -> Json.toJson(withdrawals)
      )
    )

    val newNetworkBlock = NetworkL2Block.signed(payload, actingMiner.privateKey).explicitGet()
    TestNetworkClient.send("127.0.0.1", waves1.networkPort, chainContractAddress, newNetworkBlock)

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Deposited transaction changes balances")
    val ethBalanceAfter = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    ethBalanceBefore.longValue shouldBe ethBalanceAfter.longValue - elAmount.longValue

    step("Assertion: EL height grows")
    val ecBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    ecBlockAfter.height.longValue shouldBe (ecBlockBefore.height.longValue + 1)
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

    step("Prepare: issue tokens on chain contract and transfer to a user")
    waves1.api.broadcastAndWait(
      TxHelpers.reissue(
        asset = chainContract.nativeTokenId,
        sender = chainContractAccount,
        amount = clAmount
      )
    )
    waves1.api.broadcastAndWait(
      TxHelpers.transfer(
        from = chainContractAccount,
        to = clSender.toAddress,
        amount = clAmount,
        asset = chainContract.nativeTokenId
      )
    )

    val h1 = ec1.engineApi.getLastExecutionBlock().explicitGet()
    eventually {
      val h2 = ec1.engineApi.getLastExecutionBlock().explicitGet()
      h2.height should be > h1.height
    }
  }
}

class BlockValidationTestSuite1 extends BaseDockerTestSuite {
  private implicit val httpClientBackend: SttpBackend[Identity, Any] = new LoggingBackend(HttpClientSyncBackend())
  override lazy val waves1: WavesNodeContainer = new WavesNodeContainer(
    network = network,
    number = 1,
    ip = Networks.ipForNode(3),
    baseSeed = "devnet-1",
    chainContractAddress = chainContractAddress,
    ecEngineApiUrl = ec1.engineApiDockerUrl,
    genesisConfigPath = wavesGenesisConfigPath,
    enableMining = false
  )

  "Block validation example" in {
    step(s"EL miner 12 (${miner12Account.toAddress}) join")
    waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = miner12Account,
        elRewardAddress = miner12RewardAddress
      )
    )

    step(s"EL miner 21 (${miner21Account.toAddress}) join")
    waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = miner21Account,
        elRewardAddress = miner21RewardAddress
      )
    )

    val elRecipient      = elRichAddress1
    val ethBalanceBefore = ec1.web3j.ethGetBalance(elRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance

    step(s"Wait miner 11 (${miner11Account.toAddress}) epoch")
    chainContract.waitForMinerEpoch(miner11Account)

    step("Getting last EL block before")
    val ecBlockBefore = ec1.engineApi.getLastExecutionBlock().explicitGet()

    step("Building a simulated block")
    val feeRecipient = miner11RewardAddress

    val ntpTimestamp = System.currentTimeMillis()
    val nanoTime     = System.nanoTime()
    def correctedTime(): Long = {
      val timestamp = ntpTimestamp
      val offset    = (System.nanoTime() - nanoTime) / 1000000
      timestamp + offset
    }
    val currentUnixTs   = correctedTime() / 1000
    val blockDelay      = 6
    val nextBlockUnixTs = (ecBlockBefore.timestamp + blockDelay).max(currentUnixTs)

    def calculateRandao(hitSource: ByteStr, parentHash: BlockHash): String = {
      val msg = hitSource.arr ++ HexBytesConverter.toBytes(parentHash)
      HexBytesConverter.toHex(crypto.secureHash(msg))
    }
    val currentEpochHeader = waves1.api.blockHeader(waves1.api.height()).value
    val hitSource          = ByteStr.decodeBase58(currentEpochHeader.VRF).get
    val prevRandao         = calculateRandao(hitSource, ecBlockBefore.hash)

    val withdrawals = Vector.empty

    // Transactions for invalid block
    val unexpectedDepositedTransaction = StandardBridge.mkFinalizeBridgeETHTransaction(
      transferIndex = 0L,
      standardBridgeAddress = StandardBridgeAddress,
      from = miner11RewardAddress,
      to = elRecipient,
      amount = 1
    )

    // Note: Change to `Vector.empty` for making the block valid.
    val depositedTransactions: Seq[DepositedTransaction] = Vector(unexpectedDepositedTransaction)

    val simulatedBlock: JsObject = ec1.engineApi
      .simulate(
        EmptyL2Block.mkSimulateCall(ecBlockBefore, feeRecipient, nextBlockUnixTs, prevRandao, withdrawals, depositedTransactions),
        ecBlockBefore.hash
      )
      .explicitGet()
      .head

    val simulatedBlockHash = (simulatedBlock \ "hash").as[String]

    step("Registering the simulated block on the chain contract")

    waves1.api.broadcastAndWait(
      TxHelpers.invoke(
        invoker = miner11Account,
        dApp = chainContractAddress,
        func = Some("extendMainChain_v2"),
        args = List(
          Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(ecBlockBefore.hash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(hitSource).explicitGet(),
          Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(0),
          Terms.CONST_LONG(-1)
        )
      )
    )

    step("Sending the simulated block to waves1")
    val payload = BlockToPayloadMapper.toPayloadJson(
      simulatedBlock,
      Json.obj(
        "transactions" -> depositedTransactions.map(_.toHex),
        "withdrawals"  -> Json.arr()
      )
    )
    val newNetworkBlock = NetworkL2Block.signed(payload, miner11Account.privateKey).explicitGet()
    TestNetworkClient.send("127.0.0.1", waves1.networkPort, chainContractAddress, newNetworkBlock)

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
    val ecBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    ecBlockAfter.height.longValue shouldBe ecBlockBefore.height.longValue
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
  }
}
