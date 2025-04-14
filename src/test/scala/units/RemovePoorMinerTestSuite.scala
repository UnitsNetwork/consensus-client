package units

import com.wavesplatform.TestValues
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet

class RemovePoorMinerTestSuite extends BaseTestSuite {
  private val MaxMiners = 50

  private val thisMiner = ElMinerSettings(Wallet.generateNewAccount(super.defaultSettings.walletSeed, 0))
  private val poorMiner = ElMinerSettings(TxHelpers.signer(100))
  private val newMiner  = ElMinerSettings(TxHelpers.signer(101))

  override protected val defaultSettings: TestSettings = super.defaultSettings
    .copy(
      initialMiners = List(thisMiner, poorMiner),
      additionalBalances = List(AddrWithBalance(newMiner.address))
    )
    .withEnabledElMining

  "Remove poor miner test" - {
    "< 50 active miners before cleanup" in withExtensionDomain() { d =>
      withClue("List of miners before: ") {
        val miners = d.chainContractClient.getAllActualMiners
        miners should contain(poorMiner.address)
        miners shouldNot contain(newMiner.address)
      }

      step("Poor miner transfers all funds")
      val fee            = TestValues.fee
      val transferAmount = d.balance(poorMiner.address) - fee
      val transferTxn    = TxHelpers.transfer(poorMiner.account, newMiner.address, transferAmount, fee = fee)
      val joinTxn        = d.ChainContract.join(newMiner.account, newMiner.elRewardAddress)
      d.appendBlock(transferTxn, joinTxn)

      withClue("List of miners after: ") {
        val miners = d.chainContractClient.getAllActualMiners
        miners shouldNot contain(poorMiner.address)
        miners should contain(newMiner.address)
      }
    }

    "50 active miners before cleanup" in {
      val initialMiners = thisMiner :: poorMiner :: (1 to MaxMiners - 2).map(i => ElMinerSettings(TxHelpers.signer(i))).toList
      val settings      = defaultSettings.copy(initialMiners = initialMiners)
      withExtensionDomain(settings) { d =>
        withClue("List of miners before: ") {
          val miners = d.chainContractClient.getAllActualMiners
          miners should contain(poorMiner.address)
          miners shouldNot contain(newMiner.address)
        }

        step("Poor miner transfers all funds")
        val fee            = TestValues.fee
        val transferAmount = d.balance(poorMiner.address) - fee
        val transferTxn    = TxHelpers.transfer(poorMiner.account, newMiner.address, transferAmount, fee = fee)
        val joinTxn        = d.ChainContract.join(newMiner.account, newMiner.elRewardAddress)
        d.appendBlock(transferTxn, joinTxn)

        withClue("List of miners after: ") {
          val miners = d.chainContractClient.getAllActualMiners
          miners shouldNot contain(poorMiner.address)
          miners should contain(newMiner.address)
        }
      }
    }
  }
}
