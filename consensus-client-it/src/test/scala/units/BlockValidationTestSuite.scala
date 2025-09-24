package units

import com.wavesplatform.common.utils.EitherExt2.explicitGet
import play.api.libs.json.JsObject
import units.eth.EmptyL2Block
import units.util.{BlockToPayloadMapper, HexBytesConverter}
import com.wavesplatform.common.state.ByteStr
import play.api.libs.json.*
import com.wavesplatform.state.{Height, IntegerDataEntry}
import com.wavesplatform.crypto
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.lang.v1.compiler.Terms
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.el.{DepositedTransaction, StandardBridge}

class BlockValidationTestSuite extends BaseDockerTestSuiteNoMining {

  "Block validation example" in {
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
    val hitSource  = currentHitSource
    val prevRandao = calculateRandao(hitSource, ecBlockBefore.hash)

    val withdrawals = Vector.empty

    // val depositedTransactions = Vector.empty

    val unexpectedDepositedTransaction = StandardBridge.mkFinalizeBridgeETHTransaction(
      transferIndex = 0L,
      standardBridgeAddress = StandardBridgeAddress,
      from = miner11RewardAddress,
      to = miner21RewardAddress,
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

    step("Submitting the simulated block as a new payload")
    val payload = BlockToPayloadMapper.toPayloadJson(
      simulatedBlock,
      Json.obj(
        // "transactions"          -> Json.arr(),
        "transactions" -> depositedTransactions.map(_.toHex),
        "withdrawals"  -> Json.arr()
      )
    )
    ec1.engineApi.newPayload(payload).explicitGet()

    step("Registering the simulated block on the chain contract")
    val lastWavesBlock = waves1.api.blockHeader(waves1.api.height()).value
    val txn = TxHelpers.invoke(
      invoker = miner11Account,
      dApp = chainContractAddress,
      func = Some("extendMainChain_v2"),
      args = List(
        Terms.CONST_STRING(simulatedBlockHash.drop(2)).explicitGet(),
        Terms.CONST_STRING(ecBlockBefore.hash.drop(2)).explicitGet(),
        Terms.CONST_BYTESTR(ByteStr.decodeBase58(lastWavesBlock.VRF).get).explicitGet(),
        Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
        Terms.CONST_LONG(0), // Note: passes if left -1
        Terms.CONST_LONG(-1)
      )
    )

    waves1.api.broadcastAndWait(txn) // makes EL height grow

    step("Getting last EL block after")
    val ecBlockAfter = ec1.engineApi.getLastExecutionBlock().explicitGet()
    ecBlockAfter.height.longValue shouldBe (ecBlockBefore.height.longValue + 1) // grows
    // ecBlockAfter.height.longValue shouldBe ecBlockBefore.height.longValue // doesn't grow
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
