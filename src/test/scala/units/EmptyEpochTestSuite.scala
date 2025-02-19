package units

import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.transaction.TxHelpers

class EmptyEpochTestSuite extends BaseIntegrationTestSuite {
  private val miner1 = ElMinerSettings(TxHelpers.signer(1))
  private val miner2 = ElMinerSettings(TxHelpers.signer(2))

  override protected val defaultSettings: TestSettings =
    super.defaultSettings.copy(initialMiners = List(miner1, miner2)).withEnabledElMining

  "Empty epoch" in {
    val settings = defaultSettings
    withExtensionDomain(settings) { d =>
      // Start miner1
      d.advanceNewBlocks(miner1.address)

      // Report empty epoch
      val reportResult = d.appendMicroBlockE(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          Seq.empty,
          payments = Seq(),
          invoker = miner2.account
        )
      )

      reportResult should beRight

      // End miner1
      d.advanceConsensusLayerChanged()

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("0", miner2).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      // TODO: assert balance (miner2 should get a reward)
      // TODO: assert contract state
    }
  }

  "Non-empty epoch" in {
    val settings = defaultSettings
    withExtensionDomain(settings) { d =>
      // Start miner1
      d.advanceNewBlocks(miner1.address)

      // Append a block
      val ecBlock1 = d.createEcBlockBuilder("0", miner1).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner1.account, ecBlock1))

      // Report empty epoch
      val reportResult = d.appendMicroBlockE(
        TxHelpers.invoke(
          d.chainContractAddress,
          Some("reportEmptyEpoch"),
          Seq.empty,
          payments = Seq(),
          invoker = miner2.account
        )
      )

      reportResult should matchPattern {
        case Left(err) if err.toString.contains("Current epoch is non-empty.") =>
      }

      // End miner1
      d.advanceConsensusLayerChanged()

      // Start miner2
      d.advanceNewBlocks(miner2.address)

      // Run extendMainChain
      val ecBlockAfter = d.createEcBlockBuilder("0", miner2, ecBlock1).build()
      d.appendMicroBlockAndVerify(d.ChainContract.extendMainChain(miner2.account, ecBlockAfter))

      // TODO: assert balance
      // TODO: assert contract state
    }
  }

  // TODO: add test: empty to non-empty

  // TODO: add test: several non-empty epochs
}
