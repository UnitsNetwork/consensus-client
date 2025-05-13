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
      additionalBalances = List(AddrWithBalance(newMiner.address, newMiner.wavesBalance))
    )
    .withEnabledElMining

  "A new miner exists in the list" in {
    val initialMiners = newMiner :: (1 until MaxMiners).map(i => ElMinerSettings(TxHelpers.signer(i))).toList
    val settings      = defaultSettings.copy(initialMiners = initialMiners)
    withExtensionDomain(settings) { d =>
      withClue("List of miners before: ") {
        val miners = d.chainContractClient.getAllActualMiners
        miners.size shouldBe MaxMiners
        miners should contain(newMiner.address)
      }

      step("A poor miner transfers all funds and a new miner joins")
      val joinTxn = d.ChainContract.join(newMiner.account, newMiner.elRewardAddress)
      d.appendMicroBlockE(joinTxn).left.value.getMessage should include("Already joined")
    }
  }

  "Remove poor miner test" - {
    "< 50 active miners before cleanup" in withExtensionDomain() { d =>
      withClue("List of miners before: ") {
        val miners = d.chainContractClient.getAllActualMiners
        miners.size shouldBe <=(MaxMiners)
        miners should contain(poorMiner.address)
        miners shouldNot contain(newMiner.address)
      }

      step("A poor miner transfers all funds and a new miner joins")
      val fee            = TestValues.fee
      val transferAmount = d.balance(poorMiner.address) - fee
      val transferTxn    = TxHelpers.transfer(poorMiner.account, newMiner.address, transferAmount, fee = fee)
      val joinTxn        = d.ChainContract.join(newMiner.account, newMiner.elRewardAddress)
      d.appendBlock(transferTxn, joinTxn)

      withClue("List of miners after: ") {
        val miners = d.chainContractClient.getAllActualMiners
        miners.size shouldBe <=(MaxMiners)
        miners shouldNot contain(poorMiner.address)
        miners should contain(newMiner.address)
      }
    }

    "50 active miners before cleanup and" - {
      "a new miner don't exist in the list with a poor miner" in {
        val initialMiners = thisMiner :: poorMiner :: (1 to MaxMiners - 2).map(i => ElMinerSettings(TxHelpers.signer(i))).toList
        val settings      = defaultSettings.copy(initialMiners = initialMiners)
        withExtensionDomain(settings) { d =>
          withClue("List of miners before: ") {
            val miners = d.chainContractClient.getAllActualMiners
            miners.size shouldBe MaxMiners
            miners should contain(poorMiner.address)
            miners shouldNot contain(newMiner.address)
          }

          step("A poor miner transfers all funds and a new miner joins")
          val fee            = TestValues.fee
          val transferAmount = d.balance(poorMiner.address) - fee
          val transferTxn    = TxHelpers.transfer(poorMiner.account, newMiner.address, transferAmount, fee = fee)
          val joinTxn        = d.ChainContract.join(newMiner.account, newMiner.elRewardAddress)
          d.appendBlock(transferTxn, joinTxn)

          withClue("List of miners after: ") {
            val miners = d.chainContractClient.getAllActualMiners
            miners.size shouldBe MaxMiners
            miners shouldNot contain(poorMiner.address)
            miners should contain(newMiner.address)
          }
        }
      }

      "and a new miner don't exist in the list with rich miners" in {
        val initialMiners = (1 to MaxMiners - 2).map(i => ElMinerSettings(TxHelpers.signer(i))).toList ::: thisMiner :: poorMiner :: Nil
        val settings      = defaultSettings.copy(initialMiners = initialMiners)
        withExtensionDomain(settings) { d =>
          withClue("List of miners before: ") {
            val miners = d.chainContractClient.getAllActualMiners
            log.debug(s"miners.size=${miners.toSet.size}, miners=${miners.mkString(", ")}")
            miners.size shouldBe MaxMiners
            miners should contain(poorMiner.address)
            miners shouldNot contain(newMiner.address)
          }

          step("A new miner tries to join")
          val joinTxn = d.ChainContract.join(newMiner.account, newMiner.elRewardAddress)
          d.appendBlockE(joinTxn).left.value.toString should include("Too many miners")
        }
      }
    }
  }
}
