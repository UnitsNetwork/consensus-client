package units

import com.wavesplatform.*
import com.wavesplatform.account.*
import com.wavesplatform.api.LoggingBackend
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.network.Handshake
import com.wavesplatform.state.{Height, IntegerDataEntry}
import com.wavesplatform.transaction.TxHelpers
import org.web3j.protocol.core.DefaultBlockParameterName
import play.api.libs.json.*
import sttp.client3.{HttpClientSyncBackend, Identity, SttpBackend}
import units.{BlockHash, TestNetworkClient}
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.docker.{Networks, WavesNodeContainer}
import units.el.{DepositedTransaction, StandardBridge}
import units.eth.EmptyL2Block
import units.network.BlockSpec
import units.util.{BlockToPayloadMapper, HexBytesConverter}

import scala.util.control.NonFatal

class BlockValidationTestSuite extends BaseDockerTestSuite {
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

    val depositedTxRecipient = miner21RewardAddress
    val ethBalanceBefore     = ec1.web3j.ethGetBalance(depositedTxRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance

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
      to = depositedTxRecipient,
      amount = 1
    )
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
    val txn = TxHelpers.invoke(
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

    waves1.api.broadcastAndWait(txn) // Note: Required for full block validation

    step("Submitting the simulated block as a new payload")
    val payload = BlockToPayloadMapper.toPayloadJson(
      simulatedBlock,
      Json.obj(
        "transactions" -> depositedTransactions.map(_.toHex),
        "withdrawals"  -> Json.arr()
      )
    )

    val applicationName: String = "wavesl2-" + chainContractAddress.toString.substring(0, 8)
    val applicationVersion      = (1, 5, 7)
    val nodeName                = "block-builder"
    val newNetworkBlock         = NetworkL2Block.signed(payload, miner11Account.privateKey).explicitGet()
    val handshake               = new Handshake(applicationName, applicationVersion, nodeName, 0L, None)

    val targetNodeAddress           = "127.0.0.1"
    val targetNodePorts: Array[Int] = Array(waves1.networkPort)
    sendBlock(targetNodeAddress, targetNodePorts, handshake, newNetworkBlock)

    step("Assertion: Block exists on EC1")
    eventually {
      ec1.engineApi
        .getBlockByHash(BlockHash(simulatedBlockHash))
        .explicitGet()
        .getOrElse(fail(s"Block $simulatedBlockHash was not found on EC1"))
    }

    step("Assertion: Unexpected deposited transaction doesn't affect balances")
    val ethBalanceAfter = ec1.web3j.ethGetBalance(depositedTxRecipient.toString, DefaultBlockParameterName.LATEST).send().getBalance
    ethBalanceBefore shouldBe ethBalanceAfter

    step("Assertion: After timeout the height doesn't grow")
    val ecBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    ecBlockAfter.height.longValue shouldBe ecBlockBefore.height.longValue
  }

  private def sendBlock(address: String, ports: Array[Int], handshake: Handshake, block: NetworkL2Block) = {
    val blockBytes = BlockSpec.serializeData(block)

    ports.foreach { port =>
      try {
        val client = new TestNetworkClient(address, port, handshake, blockBytes)
        log.info(s"Sending block to $address:$port")
        client.send()
      } catch {
        case NonFatal(e) =>
          log.error(s"Error sending block to $address:$port: ${e.getMessage}", e)
      }
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
  }
}
