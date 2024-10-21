package units.network.reward

import com.wavesplatform.account.KeyPair
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.transactions.Transaction
import com.wavesplatform.transactions.account.Address
import com.wavesplatform.wavesj.ApplicationStatus
import units.client.contract.HasConsensusLayerDappTxHelpers
import units.client.engine.model.BlockNumber
import units.eth.{EthAddress, Gwei}
import units.network.BaseItTestSuite

class RewardTestSuite extends BaseItTestSuite with HasConsensusLayerDappTxHelpers {
  // TODO move to base class
  override def currentHitSource: ByteStr     = ByteStr.empty
  override val chainContractAccount: KeyPair = mkKeyPair("devnet-1", 2)

  "L2-234 The reward for a previous epoch is in the first block withdrawals" in {
    log.info(s"Balance of ${chainContractAccount.toAddress}: ${waves1.api.getBalance(Address.as(chainContractAccount.toAddress.bytes))}")

    log.info("Set script")
    val setScriptTxn       = waves1.api.broadcast(Transaction.fromJson(chainContract.setScript().json().toString()))
    val setScriptTxnResult = waves1.api.waitForTransaction(setScriptTxn.id())
    if (setScriptTxnResult.applicationStatus() != ApplicationStatus.SUCCEEDED)
      fail(s"Can't setup script in ${setScriptTxn.id()}: $setScriptTxnResult")

    log.info("Setup chain contract")
    val genesisBlock = ec1.engineApi.getBlockByNumber(BlockNumber.Number(0)).explicitGet().getOrElse(fail("No EL genesis block"))
    val setupScriptTxn = waves1.api.broadcast(
      Transaction.fromJson(
        chainContract
          .setup(
            genesisBlock = genesisBlock,
            elMinerReward = Gwei.ofRawGwei(2_000_000_000L).amount.longValue(),
            invoker = chainContractAccount
          )
          .json()
          .toString()
      )
    )
    val setupScriptTxnResult = waves1.api.waitForTransaction(setupScriptTxn.id())
    if (setupScriptTxnResult.applicationStatus() != ApplicationStatus.SUCCEEDED)
      fail(s"Can't setup script in ${setScriptTxn.id()}: $setupScriptTxnResult")

    log.info("Waves miner 1 join")
    val joinTxn = waves1.api.broadcast(
      Transaction.fromJson(
        chainContract
          .join(
            minerAccount = mkKeyPair("devnet-1", 0),
            elRewardAddress = EthAddress.unsafeFrom("0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f9")
          )
          .json()
          .toString()
      )
    )
    val joinTxnResult = waves1.api.waitForTransaction(joinTxn.id())
    if (joinTxnResult.applicationStatus() != ApplicationStatus.SUCCEEDED)
      fail(s"Waves miner 1 can't join ${joinTxn.id()}: $joinTxnResult")

    def lastHeight: Long = ec1.engineApi.getLastExecutionBlock.explicitGet().height
    val h = {
      Iterator.single(lastHeight) ++
        Iterator.continually {
          Thread.sleep(1000)
          lastHeight
        }
    }.find(_ >= 2)

    log.info(s"Current height: $h")
  }
}
