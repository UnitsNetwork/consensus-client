package units.client.contract

import cats.syntax.option.*
import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.merkle.Digest
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.state.StringDataEntry
import com.wavesplatform.test.NumericExt
import com.wavesplatform.transaction.smart.{InvokeScriptTransaction, SetScriptTransaction}
import com.wavesplatform.transaction.{Asset, DataTransaction, TxHelpers}
import units.client.L2BlockLike
import units.client.contract.HasConsensusLayerDappTxHelpers.*
import units.client.contract.HasConsensusLayerDappTxHelpers.defaultFees.chainContract.*
import units.eth.{EthAddress, EthereumConstants}
import units.util.HexBytesConverter

trait HasConsensusLayerDappTxHelpers {
  def elBridgeAddress: EthAddress

  def currentHitSource: ByteStr

  def stakingContractAccount: KeyPair
  lazy val stakingContractAddress: Address = stakingContractAccount.toAddress

  def chainContractAccount: KeyPair
  lazy val chainContractAddress: Address = chainContractAccount.toAddress

  object stakingContract {
    def stakingBalance(
        minerAddress: Address,
        prevHeight: Int = 0,
        prevBalance: Long = 50_000_000L,
        nextHeight: Int = 1,
        nextBalance: Long = 10_700_000L
    ): DataTransaction = {
      require(prevHeight < nextHeight)
      TxHelpers.dataWithMultipleEntries(
        stakingContractAccount,
        List(StringDataEntry(s"%s__$minerAddress", s"%d%d%d%d__${prevHeight}__${prevBalance}__${nextHeight}__$nextBalance"))
      )
    }
  }

  object chainContract {
    def setScript(): SetScriptTransaction = TxHelpers.setScript(chainContractAccount, CompiledChainContract.script, fee = setScriptFee)

    def setup(genesisBlock: L2BlockLike, elMinerReward: Long): InvokeScriptTransaction = TxHelpers.invoke(
      dApp = chainContractAddress,
      func = "setup".some,
      args = List(
        Terms.CONST_STRING(genesisBlock.hash.drop(2)).explicitGet(),
        Terms.CONST_LONG(elMinerReward),
        Terms.CONST_STRING(s"$stakingContractAddress").explicitGet(),
        Terms.CONST_STRING(elBridgeAddress.hexNoPrefix).explicitGet()
      ),
      fee = setupFee
    )

    def join(minerAccount: KeyPair, elRewardAddress: EthAddress): InvokeScriptTransaction = TxHelpers.invoke(
      invoker = minerAccount,
      dApp = chainContractAddress,
      func = "join".some,
      args = List(Terms.CONST_BYTESTR(ByteStr(HexBytesConverter.toBytes(elRewardAddress.hexNoPrefix))).explicitGet()),
      fee = joinFee
    )

    def leave(minerAccount: KeyPair): InvokeScriptTransaction = TxHelpers.invoke(
      invoker = minerAccount,
      dApp = chainContractAddress,
      func = "leave".some,
      fee = leaveFee
    )

    def extendMainChain(
        minerAccount: KeyPair,
        block: L2BlockLike,
        e2cTransfersRootHashHex: String = EmptyE2CTransfersRootHashHex,
        lastC2ETransferIndex: Long = -1,
        vrf: ByteStr = currentHitSource
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "extendMainChain_v4".some,
        args = List(
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(vrf).explicitGet(),
          Terms.CONST_STRING(e2cTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastC2ETransferIndex)
        ),
        fee = extendMainChainFee
      )

    def appendBlock(
        minerAccount: KeyPair,
        block: L2BlockLike,
        e2cTransfersRootHashHex: String = EmptyE2CTransfersRootHashHex,
        lastC2ETransferIndex: Long = -1
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "appendBlock_v3".some,
        args = List(
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(e2cTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastC2ETransferIndex)
        ),
        fee = appendBlockFee
      )

    def startAltChain(
        minerAccount: KeyPair,
        block: L2BlockLike,
        e2cTransfersRootHashHex: String = EmptyE2CTransfersRootHashHex,
        lastC2ETransferIndex: Long = -1,
        vrf: ByteStr = currentHitSource
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "startAltChain_v4".some,
        args = List(
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(vrf).explicitGet(),
          Terms.CONST_STRING(e2cTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastC2ETransferIndex)
        ),
        fee = startAltChainFee
      )

    def extendAltChain(
        minerAccount: KeyPair,
        block: L2BlockLike,
        chainId: Long,
        e2cTransfersRootHashHex: String = EmptyE2CTransfersRootHashHex,
        lastC2ETransferIndex: Long = -1,
        vrf: ByteStr = currentHitSource
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "extendAltChain_v4".some,
        args = List(
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(vrf).explicitGet(),
          Terms.CONST_LONG(chainId),
          Terms.CONST_STRING(e2cTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastC2ETransferIndex)
        ),
        fee = extendAltChainFee
      )

    def transfer(
        sender: KeyPair,
        destElAddress: EthAddress,
        asset: Asset,
        amount: Long
    ): InvokeScriptTransaction =
      transferUnsafe(
        sender = sender,
        destElAddressHex = destElAddress.hexNoPrefix,
        asset = asset,
        amount = amount
      )

    /** @param destElAddressHex
      *   Without 0x prefix
      */
    def transferUnsafe(
        sender: KeyPair,
        destElAddressHex: String,
        asset: Asset,
        amount: Long
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = sender,
        dApp = chainContractAddress,
        func = "transfer".some,
        args = List(Terms.CONST_STRING(destElAddressHex).explicitGet()),
        payments = List(InvokeScriptTransaction.Payment(amount, asset)),
        fee = withdrawFee
      )

    def withdraw(
        sender: KeyPair,
        block: L2BlockLike,
        merkleProof: Seq[Digest],
        transferIndexInBlock: Int,
        amount: Long
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = sender,
        dApp = chainContractAddress,
        func = "withdraw".some,
        args = List(
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.ARR(merkleProof.map[Terms.EVALUATED](x => Terms.CONST_BYTESTR(ByteStr(x)).explicitGet()).toVector, limited = false).explicitGet(),
          Terms.CONST_LONG(transferIndexInBlock),
          Terms.CONST_LONG(amount)
        ),
        fee = withdrawFee
      )
  }
}

object HasConsensusLayerDappTxHelpers {
  val EmptyE2CTransfersRootHashHex = EthereumConstants.NullHex

  object defaultFees {
    object chainContract {
      val setScriptFee       = 0.05.waves
      val setupFee           = 2.waves
      val joinFee            = 0.1.waves
      val leaveFee           = 0.1.waves
      val extendMainChainFee = 0.1.waves
      val appendBlockFee     = 0.1.waves
      val startAltChainFee   = 0.1.waves
      val extendAltChainFee  = 0.1.waves
      val transferFee        = 0.1.waves
      val withdrawFee        = 0.1.waves
    }
  }
}
