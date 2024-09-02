package units.client.contract

import cats.syntax.option.*
import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.merkle.Digest
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import units.client.L2BlockLike
import units.client.contract.HasConsensusLayerDappTxHelpers.*
import units.eth.{EthAddress, EthereumConstants}
import units.util.HexBytesConverter
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.state.StringDataEntry
import com.wavesplatform.test.NumericExt
import com.wavesplatform.transaction.smart.{InvokeScriptTransaction, SetScriptTransaction}
import com.wavesplatform.transaction.{Asset, DataTransaction, TxHelpers}

trait HasConsensusLayerDappTxHelpers {
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
    val setScriptFee                      = 0.05.waves
    def setScript(): SetScriptTransaction = TxHelpers.setScript(chainContractAccount, CompiledChainContract.script, fee = setScriptFee)

    val setupFee = 2.waves
    def setup(genesisBlock: L2BlockLike, elMinerReward: Long, elBridgeAddress: EthAddress): InvokeScriptTransaction = TxHelpers.invoke(
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

    val joinFee = 0.1.waves
    def join(minerAccount: KeyPair, elRewardAddress: EthAddress): InvokeScriptTransaction = TxHelpers.invoke(
      invoker = minerAccount,
      dApp = chainContractAddress,
      func = "join".some,
      args = List(Terms.CONST_BYTESTR(ByteStr(HexBytesConverter.toBytes(elRewardAddress.hexNoPrefix))).explicitGet()),
      fee = joinFee
    )

    val leaveFee = 0.1.waves
    def leave(minerAccount: KeyPair): InvokeScriptTransaction = TxHelpers.invoke(
      invoker = minerAccount,
      dApp = chainContractAddress,
      func = "leave".some,
      fee = leaveFee
    )

    val extendMainChainV3Fee = 0.1.waves
    def extendMainChainV3(
        minerAccount: KeyPair,
        block: L2BlockLike,
        epoch: Long,
        elToClTransfersRootHashHex: String = EmptyElToClTransfersRootHashHex,
        lastElToClTransferIndex: Long = -1
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "extendMainChain_v3".some,
        args = List(
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_LONG(epoch),
          Terms.CONST_STRING(elToClTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastElToClTransferIndex)
        ),
        fee = extendMainChainV3Fee
      )

    val appendBlockV3Fee = 0.1.waves
    def appendBlockV3(
        minerAccount: KeyPair,
        block: L2BlockLike,
        elToClTransfersRootHashHex: String = EmptyElToClTransfersRootHashHex,
        lastElToClTransferIndex: Long = -1
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "appendBlock_v3".some,
        args = List(
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(elToClTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastElToClTransferIndex)
        ),
        fee = appendBlockV3Fee
      )

    val startAltChainV3Fee = 0.1.waves
    def startAltChainV3(
        minerAccount: KeyPair,
        block: L2BlockLike,
        epoch: Long,
        elToClTransfersRootHashHex: String = EmptyElToClTransfersRootHashHex,
        lastElToClTransferIndex: Long = -1
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "startAltChain_v3".some,
        args = List(
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_LONG(epoch),
          Terms.CONST_STRING(elToClTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastElToClTransferIndex)
        ),
        fee = startAltChainV3Fee
      )

    val extendAltChainV3Fee = 0.1.waves
    def extendAltChainV3(
        minerAccount: KeyPair,
        chainId: Long,
        block: L2BlockLike,
        epoch: Long,
        elToClTransfersRootHashHex: String = EmptyElToClTransfersRootHashHex,
        lastElToClTransferIndex: Long = -1
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "extendAltChain_v3".some,
        args = List(
          Terms.CONST_LONG(chainId),
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_LONG(epoch),
          Terms.CONST_STRING(elToClTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastElToClTransferIndex)
        ),
        fee = extendAltChainV3Fee
      )

    val transferFee = 0.1.waves
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

    val withdrawFee = 0.1.waves
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
  val EmptyElToClTransfersRootHashHex = EthereumConstants.NullHex
}