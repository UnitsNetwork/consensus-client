package units.client.contract

import cats.syntax.option.*
import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.merkle.Digest
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.lang.v1.compiler.Terms.{CONST_LONG, CONST_STRING}
import com.wavesplatform.state.{BooleanDataEntry, DataEntry, EmptyDataEntry, IntegerDataEntry, StringDataEntry}
import com.wavesplatform.test.NumericExt
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.smart.{InvokeScriptTransaction, SetScriptTransaction}
import com.wavesplatform.transaction.{Asset, DataTransaction, TxHelpers}
import units.client.L2BlockLike
import units.client.contract.HasConsensusLayerDappTxHelpers.*
import units.client.contract.HasConsensusLayerDappTxHelpers.DefaultFees.ChainContract.*
import units.eth.{EthAddress, EthereumConstants}
import units.{BlockHash, ELUpdater}

trait HasConsensusLayerDappTxHelpers {
  def currentHitSource: ByteStr

  def chainContractAccount: KeyPair
  lazy val chainContractAddress: Address = chainContractAccount.toAddress

  def chainRegistryAccount: KeyPair
  lazy val chainRegistryAddress: Address = chainRegistryAccount.toAddress

  object ChainRegistry {
    def approve(chainContract: Address = chainContractAddress): DataTransaction =
      setStatus(BooleanDataEntry(ELUpdater.registryKey(chainContract), value = true))

    def reject(chainContract: Address = chainContractAddress): DataTransaction =
      setStatus(BooleanDataEntry(ELUpdater.registryKey(chainContract), value = false))

    def setStatus(status: DataEntry[?]): DataTransaction =
      TxHelpers.data(
        account = chainRegistryAccount,
        entries = List(status)
      )
  }

  object ChainContract {
    def setScript(): SetScriptTransaction = TxHelpers.setScript(chainContractAccount, CompiledChainContract.script, fee = setScriptFee, version = 2)

    def stop(invoker: KeyPair = chainContractAccount): InvokeScriptTransaction = TxHelpers.invoke(
      dApp = chainContractAddress,
      func = "stop".some,
      fee = stopFee,
      invoker = invoker
    )
    def continue(): DataTransaction = TxHelpers.dataV2(chainContractAccount, Seq(EmptyDataEntry("stopped")))

    def setup(
        genesisBlock: L2BlockLike,
        elMinerReward: Long,
        daoAddress: Option[Address],
        daoReward: Long,
        invoker: KeyPair = chainContractAccount
    ): InvokeScriptTransaction = TxHelpers.invoke(
      dApp = chainContractAddress,
      func = "setup".some,
      args = List(
        Terms.CONST_STRING(genesisBlock.hash.drop(2)).explicitGet(),
        Terms.CONST_LONG(elMinerReward),
        Terms.CONST_STRING(daoAddress.fold("")(_.toString)).explicitGet(),
        Terms.CONST_LONG(daoReward)
      ),
      fee = setupFee,
      invoker = invoker
    )

    def enableTokenTransfersWithWaves(standardBridge: EthAddress, wwaves: EthAddress, activationEpoch: Int): InvokeScriptTransaction =
      TxHelpers.invoke(
        chainContractAddress,
        Some("enableTokenTransfers"),
        Seq(CONST_STRING(standardBridge.hex.drop(2)).explicitGet(), CONST_STRING(wwaves.hex.drop(2)).explicitGet(), CONST_LONG(activationEpoch)),
        invoker = chainContractAccount,
        fee = 0.009.waves
      )

    def enableTokenTransfers(standardBridge: EthAddress, activationEpoch: Int): DataTransaction =
      TxHelpers.data(
        chainContractAccount,
        Seq(
          IntegerDataEntry("assetTransfersActivationEpoch", activationEpoch),
          StringDataEntry("elStandardBridgeAddress", standardBridge.hex)
        ),
        fee = 0.009.waves
      )

    def enableStrictTransfers(activationEpoch: Int): DataTransaction =
      TxHelpers.data(
        chainContractAccount,
        Seq(IntegerDataEntry("strictC2ETransfersActivationEpoch", activationEpoch)),
        fee = 0.009.waves
      )

    def enableNativeTokenTransfersViaDeposits(activationEpoch: Int): DataTransaction =
      TxHelpers.data(
        chainContractAccount,
        Seq(IntegerDataEntry("nativeTokenDepositTransfersActivationEpoch", activationEpoch)),
        fee = 0.009.waves
      )

    def join(minerAccount: KeyPair, elRewardAddress: EthAddress): InvokeScriptTransaction = TxHelpers.invoke(
      invoker = minerAccount,
      dApp = chainContractAddress,
      func = "join".some,
      args = List(Terms.CONST_STRING(elRewardAddress.hexNoPrefix).explicitGet()),
      fee = joinFee
    )

    def leave(minerAccount: KeyPair): InvokeScriptTransaction = TxHelpers.invoke(
      invoker = minerAccount,
      dApp = chainContractAddress,
      func = "leave".some,
      fee = leaveFee
    )

    def registerAsset(
        asset: IssuedAsset,
        erc20Address: EthAddress,
        elDecimals: Int,
        invoker: KeyPair = chainContractAccount
    ): InvokeScriptTransaction =
      registerAsset(asset, erc20Address.hexNoPrefix, elDecimals, invoker)

    def registerAsset(asset: IssuedAsset, erc20AddressHex: String, elDecimals: Int, invoker: KeyPair): InvokeScriptTransaction =
      registerAssets(List(asset), List(erc20AddressHex), List(elDecimals), invoker)

    def registerAssets(assets: List[IssuedAsset], erc20AddressHex: List[String], elDecimals: List[Int], invoker: KeyPair): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = invoker,
        dApp = chainContractAddress,
        func = "registerAssets".some,
        args = List(
          Terms
            .ARR(
              assets.map(x => Terms.CONST_STRING(x.id.toString).explicitGet()).toVector,
              limited = true
            )
            .explicitGet(),
          Terms
            .ARR(
              erc20AddressHex.map(x => Terms.CONST_STRING(x).explicitGet()).toVector,
              limited = true
            )
            .explicitGet(),
          Terms
            .ARR(
              elDecimals.map(Terms.CONST_LONG(_)).toVector,
              limited = true
            )
            .explicitGet()
        ),
        fee = 0.009.waves
      )

    def issueAndRegister(
        erc20Address: EthAddress,
        elDecimals: Int,
        name: String,
        description: String,
        clDecimals: Int,
        invoker: KeyPair = chainContractAccount
    ): InvokeScriptTransaction = issueAndRegister(erc20Address.hexNoPrefix, elDecimals, name, description, clDecimals, invoker)

    def issueAndRegister(
        erc20AddressHex: String,
        elDecimals: Int,
        name: String,
        description: String,
        clDecimals: Int,
        invoker: KeyPair
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = invoker,
        dApp = chainContractAddress,
        func = "issueAndRegister".some,
        args = List(
          Terms.CONST_STRING(erc20AddressHex).explicitGet(),
          Terms.CONST_LONG(elDecimals),
          Terms.CONST_STRING(name).explicitGet(),
          Terms.CONST_STRING(description).explicitGet(),
          Terms.CONST_LONG(clDecimals)
        ),
        fee = issueAndRegisterFee
      )

    def extendMainChain(
        minerAccount: KeyPair,
        block: L2BlockLike,
        e2cTransfersRootHashHex: String = EmptyE2CTransfersRootHashHex,
        lastC2ETransferIndex: Long = -1,
        lastAssetRegistryIndex: Int = -1,
        vrf: ByteStr = currentHitSource
    ): InvokeScriptTransaction =
      extendMainChain(minerAccount, block.hash, block.parentHash, e2cTransfersRootHashHex, lastC2ETransferIndex, lastAssetRegistryIndex, vrf)

    def extendMainChain(
        minerAccount: KeyPair,
        blockHash: BlockHash,
        parentBlockHash: BlockHash,
        e2cTransfersRootHashHex: String,
        lastC2ETransferIndex: Long,
        lastAssetRegistryIndex: Int,
        vrf: ByteStr
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "extendMainChain_v2".some,
        args = List(
          Terms.CONST_STRING(blockHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(parentBlockHash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(vrf).explicitGet(),
          Terms.CONST_STRING(e2cTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastC2ETransferIndex),
          Terms.CONST_LONG(lastAssetRegistryIndex)
        ),
        fee = extendMainChainFee
      )

    def appendBlock(
        minerAccount: KeyPair,
        block: L2BlockLike,
        e2cTransfersRootHashHex: String = EmptyE2CTransfersRootHashHex,
        lastC2ETransferIndex: Long = -1,
        lastAssetRegistryIndex: Int = -1
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "appendBlock_v2".some,
        args = List(
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_STRING(e2cTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastC2ETransferIndex),
          Terms.CONST_LONG(lastAssetRegistryIndex)
        ),
        fee = appendBlockFee
      )

    def startAltChain(
        minerAccount: KeyPair,
        block: L2BlockLike,
        e2cTransfersRootHashHex: String = EmptyE2CTransfersRootHashHex,
        lastC2ETransferIndex: Long = -1,
        lastAssetRegistryIndex: Int = -1,
        vrf: ByteStr = currentHitSource
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "startAltChain_v2".some,
        args = List(
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(vrf).explicitGet(),
          Terms.CONST_STRING(e2cTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastC2ETransferIndex),
          Terms.CONST_LONG(lastAssetRegistryIndex)
        ),
        fee = startAltChainFee
      )

    def extendAltChain(
        minerAccount: KeyPair,
        block: L2BlockLike,
        chainId: Long,
        e2cTransfersRootHashHex: String = EmptyE2CTransfersRootHashHex,
        lastC2ETransferIndex: Long = -1,
        lastAssetRegistryIndex: Int = -1,
        vrf: ByteStr = currentHitSource
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = minerAccount,
        dApp = chainContractAddress,
        func = "extendAltChain_v2".some,
        args = List(
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_BYTESTR(vrf).explicitGet(),
          Terms.CONST_LONG(chainId),
          Terms.CONST_STRING(e2cTransfersRootHashHex.drop(2)).explicitGet(),
          Terms.CONST_LONG(lastC2ETransferIndex),
          Terms.CONST_LONG(lastAssetRegistryIndex)
        ),
        fee = extendAltChainFee
      )

    def transfer(
        sender: KeyPair,
        destElAddress: EthAddress,
        asset: Asset,
        amount: Long,
        timestamp: Long = TxHelpers.timestamp
    ): InvokeScriptTransaction =
      transferUnsafe(
        sender = sender,
        destElAddressHex = destElAddress.hexNoPrefix,
        asset = asset,
        amount = amount,
        timestamp = timestamp
      )

    /** @param destElAddressHex
      *   Without 0x prefix
      */
    def transferUnsafe(
        sender: KeyPair,
        destElAddressHex: String,
        asset: Asset,
        amount: Long,
        timestamp: Long = TxHelpers.timestamp
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = sender,
        dApp = chainContractAddress,
        func = "transfer".some,
        args = List(Terms.CONST_STRING(destElAddressHex).explicitGet()),
        payments = List(InvokeScriptTransaction.Payment(amount, asset)),
        fee = transferFee,
        timestamp = timestamp
      )

    def withdraw(
        sender: KeyPair,
        block: L2BlockLike,
        merkleProof: Seq[Digest],
        transferIndexInBlock: Int,
        amount: Long
    ): InvokeScriptTransaction = withdraw(sender, block.hash, merkleProof, transferIndexInBlock, amount)

    def withdraw(
        sender: KeyPair,
        blockHash: BlockHash,
        merkleProof: Seq[Digest],
        transferIndexInBlock: Int,
        amount: Long
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = sender,
        dApp = chainContractAddress,
        func = "withdraw".some,
        args = List(
          Terms.CONST_STRING(blockHash.drop(2)).explicitGet(),
          Terms.ARR(merkleProof.map[Terms.EVALUATED](x => Terms.CONST_BYTESTR(ByteStr(x)).explicitGet()).toVector, limited = false).explicitGet(),
          Terms.CONST_LONG(transferIndexInBlock),
          Terms.CONST_LONG(amount)
        ),
        fee = withdrawFee
      )

    def withdrawAsset(
        sender: KeyPair,
        blockHash: BlockHash,
        merkleProof: Seq[Digest],
        transferIndexInBlock: Int,
        amount: Long,
        asset: Asset
    ): InvokeScriptTransaction =
      TxHelpers.invoke(
        invoker = sender,
        dApp = chainContractAddress,
        func = "withdrawAsset".some,
        args = List(
          Terms.CONST_STRING(blockHash.drop(2)).explicitGet(),
          Terms.ARR(merkleProof.map[Terms.EVALUATED](x => Terms.CONST_BYTESTR(ByteStr(x)).explicitGet()).toVector, limited = false).explicitGet(),
          Terms.CONST_LONG(transferIndexInBlock),
          Terms.CONST_LONG(amount),
          Terms.CONST_STRING(asset.fold("WAVES")(_.id.toString)).explicitGet()
        ),
        fee = withdrawFee
      )

    def reportEmptyEpoch(minerAccount: KeyPair, vrf: ByteStr = currentHitSource): InvokeScriptTransaction = TxHelpers.invoke(
      invoker = minerAccount,
      dApp = chainContractAddress,
      func = "reportEmptyEpoch".some,
      args = List(Terms.CONST_BYTESTR(vrf).explicitGet()),
      fee = reportEmptyEpochFee
    )

    def claimEmptyEpochReportRewards(minerAccount: KeyPair, epochNumbers: Seq[Long]): InvokeScriptTransaction = TxHelpers.invoke(
      invoker = minerAccount,
      dApp = chainContractAddress,
      func = "claimEmptyEpochReportRewards".some,
      args = List(Terms.ARR(epochNumbers.map(Terms.CONST_LONG(_)).toVector, limited = true).explicitGet()),
      fee = claimEmptyEpochReportRewardsFee
    )
  }
}

object HasConsensusLayerDappTxHelpers {
  val EmptyE2CTransfersRootHashHex = EthereumConstants.NullHex

  object DefaultFees {
    object ChainContract {
      val setScriptFee                    = 0.05.waves
      val setupFee                        = 2.waves
      val joinFee                         = 0.1.waves
      val leaveFee                        = 0.1.waves
      val extendMainChainFee              = 0.1.waves
      val appendBlockFee                  = 0.1.waves
      val startAltChainFee                = 0.1.waves
      val extendAltChainFee               = 0.1.waves
      val transferFee                     = 0.1.waves
      val withdrawFee                     = 0.1.waves
      val issueAndRegisterFee             = 1.009.waves
      val reportEmptyEpochFee             = 0.1.waves
      val claimEmptyEpochReportRewardsFee = 0.1.waves
      val stopFee                         = 0.1.waves
    }
  }
}
