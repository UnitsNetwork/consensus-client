package units

import com.wavesplatform.TestValues
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet

class RemovePoorMinerTestSuite extends BaseTestSuite {
  private val thisMiner = ElMinerSettings(Wallet.generateNewAccount(super.defaultSettings.walletSeed, 0))
  private val miner1    = ElMinerSettings(TxHelpers.signer(0))
  private val miner2    = ElMinerSettings(TxHelpers.signer(1))

  override protected val defaultSettings: TestSettings = super.defaultSettings
    .copy(
      initialMiners = List(thisMiner, miner1),
      additionalBalances = List(AddrWithBalance(miner2.address))
    )
    .withEnabledElMining

  "Remove poor miner test" in withExtensionDomain() { d =>
    withClue("In the list of miners before: ") {
      d.chainContractClient.getAllActualMiners should contain(miner1.address)
    }

    step("Miner1 transfers all funds")
    val fee            = TestValues.fee
    val transferAmount = d.balance(miner1.address) - fee
    val transferTxn    = TxHelpers.transfer(miner1.account, miner2.address, transferAmount, fee = fee)
    val joinTxn        = d.ChainContract.join(miner2.account, miner2.elRewardAddress)
    d.appendBlock(transferTxn, joinTxn)

    withClue("In the list of miners before: ") {
      d.chainContractClient.getAllActualMiners shouldNot contain(miner1.address)
    }
  }
}
