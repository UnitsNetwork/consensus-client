package units

import com.wavesplatform.account.KeyPair
import com.wavesplatform.api.LoggingBackend.LoggingOptions
import com.wavesplatform.api.http.ApiError.ScriptExecutionError
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.transaction.TxHelpers
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex

import scala.annotation.tailrec

class AlternativeChainTestSuite extends BaseDockerTestSuite {
  "L2-383 Start an alternative chain after not getting an EL-block" in {
    step(s"Wait miner 1 (${miner11Account.toAddress}) forge at least one block")
    chainContract.waitForHeight(1L)

    step(s"EL miner 2 (${miner21Account.toAddress}) join")
    val heightBeforeJoin = waves1.api.height()
    waves1.api.broadcastAndWait(
      ChainContract.join(
        minerAccount = miner21Account,
        elRewardAddress = miner21RewardAddress
      )
    )

    step(s"Wait miner 2 (${miner21Account.toAddress}) epoch and issue a block confirmation")
    waves1.api.waitForHeight(heightBeforeJoin + 1)
    broadcastElBlockConfirmation(miner21Account)

    step(s"Wait miner 1 (${miner11Account.toAddress}) epoch")
    chainContract.waitForMinerEpoch(miner11Account)

    step("Checking an alternative chain started")
    chainContract.waitForChainId(1L)
  }

  @tailrec private def broadcastElBlockConfirmation(minerAccount: KeyPair, maxAttempts: Int = 5)(implicit
      loggingOptions: LoggingOptions = LoggingOptions(logRequest = false)
  ): Unit = {
    if (maxAttempts == 0) fail("Can't broadcast an EL-block confirmation: all attempts are out")

    chainContract.waitForMinerEpoch(minerAccount)
    val lastContractBlock = chainContract.getLastBlockMeta(0).value
    val lastWavesBlock    = waves1.api.blockHeader(waves1.api.height()).value
    val txn = ChainContract.extendMainChain(
      minerAccount = minerAccount,
      blockHash = BlockHash("0x0000000000000000000000000000000000000000000000000000000000000001"),
      parentBlockHash = lastContractBlock.hash,
      e2cTransfersRootHashHex = EmptyE2CTransfersRootHashHex,
      lastC2ETransferIndex = -1,
      lastAssetRegistryIndex = -1,
      vrf = ByteStr.decodeBase58(lastWavesBlock.VRF).get
    )

    // TODO:
//    val txn = TxHelpers.invoke(
//      invoker = minerAccount,
//      dApp = chainContractAddress,
//      func = Some("extendMainChain"),
//      args = List(
//        Terms.CONST_STRING("0000000000000000000000000000000000000000000000000000000000000001").explicitGet(),
//        Terms.CONST_STRING(lastContractBlock.hash.drop(2)).explicitGet(),
//        Terms.CONST_BYTESTR(ByteStr.decodeBase58(lastWavesBlock.VRF).get).explicitGet(),
//        Terms.CONST_STRING(EmptyE2CTransfersRootHashHex.drop(2)).explicitGet(),
//        Terms.CONST_LONG(-1)
//      )
//    )
    waves1.api.broadcast(txn) match {
      case Left(e) if e.error == ScriptExecutionError.Id =>
        log.debug(s"Failed to send an EL-block confirmation: $e")
        broadcastElBlockConfirmation(minerAccount, maxAttempts - 1)
      case Left(e) => fail(s"Can't broadcast an EL-block confirmation: $e")
      case _       => waves1.api.waitForSucceeded(txn.id())
    }
  }
}
