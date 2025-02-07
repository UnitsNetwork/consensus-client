package units.client.contract

import cats.implicits.*
import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.block.BlockHeader
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.consensus.{FairPoSCalculator, PoSCalculator}
import com.wavesplatform.lang.Global
import com.wavesplatform.serialization.ByteBufferOps
import com.wavesplatform.state.{BinaryDataEntry, Blockchain, DataEntry, EmptyDataEntry, IntegerDataEntry, StringDataEntry}
import com.wavesplatform.transaction.Asset
import units.BlockHash
import units.client.contract.ChainContractClient.*
import units.eth.{EthAddress, Gwei}
import units.util.HexBytesConverter

import java.nio.ByteBuffer
import scala.reflect.ClassTag

trait ChainContractClient {
  def contract: Address

  def extractData(key: String): Option[DataEntry[?]]

  def isContractSetup: Boolean = getLongData("minerReward").isDefined

  def getLastBlockMeta(chainId: Long): Option[ContractBlock] =
    for {
      hash      <- getLastBlockHash(chainId)
      blockMeta <- getBlock(hash)
    } yield blockMeta

  def getFirstBlockMeta(chainId: Long): Option[ContractBlock] =
    for {
      hash      <- getFirstBlockHash(chainId)
      blockMeta <- getBlock(hash)
    } yield blockMeta

  def getSupporters(chainId: Long): Set[Address] =
    getStringData(s"chain${chainId}Supporters").toSet
      .flatMap[String](_.split(Sep))
      .flatMap(Address.fromString(_).toOption)

  def getAllActualMiners: Seq[Address] =
    getStringData(AllMinersKey).toSeq
      .flatMap(_.split(Sep))
      .flatMap(Address.fromString(_).toOption)

  def getElRewardAddress(miner: Address): Option[EthAddress] = getElRewardAddress(ByteStr(miner.bytes))
  private def getElRewardAddress(minerAddress: ByteStr): Option[EthAddress] =
    extractData(s"miner_${minerAddress}_RewardAddress")
      .orElse(extractData(s"miner${minerAddress}RewardAddress"))
      .collect {
        case StringDataEntry(_, v) => EthAddress.unsafeFrom(v)
        case BinaryDataEntry(_, v) => EthAddress.unsafeFrom(v.arr)
      }

  def getBlock(hash: BlockHash): Option[ContractBlock] =
    getBinaryData(s"block_$hash").orElse(getBinaryData(s"blockMeta${clean(hash)}")).map { blockMeta =>
      val bb = ByteBuffer.wrap(blockMeta.arr)
      try {
        val chainHeight = bb.getLong()
        val epoch       = bb.getLong().toInt // blockMeta is set up in a chain contract and RIDE numbers are Longs
        val parentHash  = BlockHash(bb.getByteArray(BlockHashBytesSize))
        val chainId     = if (bb.remaining() >= 8) bb.getLong() else DefaultMainChainId

        if (epoch < getAssetTransfersActivationEpoch) {
          val e2cNativeTransfersRootHash =
            if (bb.remaining() >= ContractBlock.E2CTransfersRootHashLength) bb.getByteArray(ContractBlock.E2CTransfersRootHashLength)
            else Array.emptyByteArray

          val lastC2ENativeTransferIndex = if (bb.remaining() >= 8) bb.getLong() else -1L

          require(
            !bb.hasRemaining,
            s"Not parsed ${bb.remaining()} bytes from ${blockMeta.base64}, read data: " +
              s"chainHeight=$chainHeight, epoch=$epoch, parentHash=$parentHash, chainId=$chainId, " +
              s"e2cNativeTransfersRootHash=${HexBytesConverter.toHex(e2cNativeTransfersRootHash)}, lastC2ENativeTransferIndex=$lastC2ENativeTransferIndex"
          )

          val epochMeta = getEpochMeta(epoch).getOrElse(fail(s"Can't find epoch meta for epoch $epoch"))

          val minerRewardElAddress =
            if (chainHeight == 0) EthAddress.empty
            else getElRewardAddress(epochMeta.miner).getOrElse(fail(s"Can't find a reward address for generator ${epochMeta.miner}"))

          ContractBlock(
            hash,
            parentHash,
            epoch,
            chainHeight,
            minerRewardElAddress,
            chainId,
            e2cNativeTransfersRootHash,
            lastC2ENativeTransferIndex,
            Array.emptyByteArray,
            -1,
            -1
          )
        } else {
          val transfersFlag = bb.getByte

          val e2cNativeTransfersRootHash =
            if ((transfersFlag & NativeTransfersFlag) == NativeTransfersFlag) bb.getByteArray(ContractBlock.E2CTransfersRootHashLength)
            else Array.emptyByteArray

          val lastC2ENativeTransferIndex = bb.getLong()

          val e2cAssetTransfersRootHash =
            if ((transfersFlag & AssetTransfersFlag) == AssetTransfersFlag) bb.getByteArray(ContractBlock.E2CTransfersRootHashLength)
            else Array.emptyByteArray

          val lastC2EAssetTransferIndex = bb.getLong()

          val lastAssetRegistryIndex = bb.getLong()

          require(
            !bb.hasRemaining,
            s"Not parsed ${bb.remaining()} bytes from ${blockMeta.base64}, read data: " +
              s"chainHeight=$chainHeight, epoch=$epoch, parentHash=$parentHash, chainId=$chainId, " +
              s"e2cNativeTransfersRootHash=${HexBytesConverter.toHex(e2cNativeTransfersRootHash)}, lastC2ENativeTransferIndex=$lastC2ENativeTransferIndex, " +
              s"e2cAssetTransfersRootHash=${HexBytesConverter.toHex(e2cAssetTransfersRootHash)}, lastC2EAssetTransferIndex=$lastC2EAssetTransferIndex, " +
              s"lastAssetRegistryIndex=$lastAssetRegistryIndex"
          )

          val epochMeta = getEpochMeta(epoch).getOrElse(fail(s"Can't find epoch meta for epoch $epoch"))

          val minerRewardElAddress =
            if (chainHeight == 0) EthAddress.empty
            else getElRewardAddress(epochMeta.miner).getOrElse(fail(s"Can't find a reward address for generator ${epochMeta.miner}"))

          ContractBlock(
            hash,
            parentHash,
            epoch,
            chainHeight,
            minerRewardElAddress,
            chainId,
            e2cNativeTransfersRootHash,
            lastC2ENativeTransferIndex,
            e2cAssetTransfersRootHash,
            lastC2EAssetTransferIndex,
            if (lastAssetRegistryIndex.isValidInt) lastAssetRegistryIndex.toInt
            else fail(s"$lastAssetRegistryIndex is not a valid int")
          )
        }
      } catch {
        case e: Throwable => fail(s"Can't read a block $hash meta, bytes: ${blockMeta.base64}, remaining: ${bb.remaining()}", e)
      }
    }

  def getLastChainId: Long =
    getLongData("lastChainId").getOrElse(DefaultMainChainId)

  def getFirstValidAltChainId: Long =
    getLongData("firstValidAltChainId").getOrElse(DefaultMainChainId)

  def getMainChainIdOpt: Option[Long] =
    getLongData(MainChainIdKey)

  def getMainChainId: Long =
    getMainChainIdOpt.getOrElse(DefaultMainChainId)

  def getEpochMeta(epoch: Int): Option[EpochContractMeta] = getStringData(f"epoch_$epoch%08d").flatMap { s =>
    val items = s.split(Sep)
    if (items.length == 3) for {
      a <- Address.fromString(items(0)).toOption
      e <- items(1).toIntOption
    } yield EpochContractMeta(a, e, BlockHash(s"0x${items(2)}"))
    else None
  }

  def blockExists(hash: BlockHash): Boolean = getBlock(hash).isDefined

  def getMainChainInfo: Option[ChainInfo] =
    getChainInfo(getMainChainId)

  def getChainInfo(chainId: Long): Option[ChainInfo] = {
    val isMainChain = getMainChainId == chainId
    val firstBlock  = getFirstBlockMeta(chainId)
    val lastBlock   = getLastBlockMeta(chainId)

    val args = (firstBlock, lastBlock)
    val definedArgs = args.productIterator.count {
      case Some(_) => true
      case _       => false
    }

    if (definedArgs == 0) None
    else if (definedArgs == args.productArity) args.mapN(ChainInfo(chainId, isMainChain, _, _))
    else fail(s"Can't get chain $chainId info, one of fields is empty, first block: $firstBlock, last block: $lastBlock")
  }

  def getFinalizedBlockHash: BlockHash =
    getStringData("finalizedBlock")
      .map(hash => BlockHash(s"0x$hash"))
      .getOrElse(throw new IllegalStateException("Can't get finalized block hash: not found at contract"))

  def getFinalizedBlock: ContractBlock = {
    val hash = getFinalizedBlockHash
    getBlock(hash).getOrElse(throw new IllegalStateException(s"Can't get finalized block $hash info: not found at contract"))
  }

  private def calculateMinerDelay(
      hitSource: Array[Byte],
      baseTarget: Long,
      miner: Address,
      blockchain: Blockchain
  ): Option[(Address, Long)] = {
    val hit               = Global.blake2b256(hitSource ++ miner.bytes).take(PoSCalculator.HitSize)
    val generatingBalance = blockchain.generatingBalance(miner)
    if (generatingBalance >= MinMinerBalance) {
      // See WavesEnvironment.calculateDelay
      val delay = FairPoSCalculator(0, 0).calculateDelay(BigInt(1, hit), baseTarget, generatingBalance)
      Some(miner -> delay)
    } else None
  }

  def calculateEpochMiner(header: BlockHeader, hitSource: ByteStr, epochNumber: Int, blockchain: Blockchain): Either[String, Address] =
    getAllActualMiners
      .flatMap(miner => calculateMinerDelay(hitSource.arr, header.baseTarget, miner, blockchain))
      .minByOption(_._2)
      .map(_._1)
      .toRight(s"No miner for epoch $epochNumber")

  private def getBlockHash(key: String): Option[BlockHash] =
    extractData(key).collect {
      case StringDataEntry(_, value) => BlockHash(HexBytesConverter.toBytes(value))
      case BinaryDataEntry(_, value) => BlockHash(value)
    }

  def getMinerPublicKey(rewardAddress: EthAddress): Option[PublicKey] =
    getBinaryData(s"miner_${rewardAddress}_PK")
      .orElse(getBinaryData(s"miner${rewardAddress}PK"))
      .orElse(getBinaryData(s"miner${rewardAddress.hexNoPrefix}PK"))
      .map(PublicKey(_))

  def getOptions: ChainContractOptions = ChainContractOptions(
    miningReward = getLongData("minerReward")
      .map(Gwei.ofRawGwei)
      .getOrElse(throw new IllegalStateException("minerReward is empty on contract")),
    elNativeBridgeAddress = getStringData("elBridgeAddress")
      .map(EthAddress.unsafeFrom)
      .getOrElse(throw new IllegalStateException("elBridgeAddress is empty on contract")),
    elStandardBridgeAddress = getStringData("elStandardBridgeAddress")
      .map(EthAddress.unsafeFrom)
      .getOrElse(throw new IllegalStateException("elStandardBridgeAddress is empty on contract")),
    assetTransfersActivationEpoch = getAssetTransfersActivationEpoch
  )

  private def getAssetTransfersActivationEpoch: Long = getLongData("assetTransfersActivationEpoch").getOrElse(Long.MaxValue)

  private def getChainMeta(chainId: Long): Option[(Int, BlockHash)] = {
    val key = f"chain_$chainId%08d"
    getStringData(key).map { s =>
      val items = s.split(Sep)
      if (items.length != 2) fail(s"Expected 2 data items in key '$key', got ${items.length}: $s")

      val height = items(0).toIntOption.getOrElse(fail(s"Expected a height at #0, got: ${items(0)}"))
      val lastBlockHash =
        try BlockHash(HexBytesConverter.toBytes(items(1)))
        catch {
          case e: Throwable => fail(s"Expected a block hash at #1, got: ${items(1)}", e)
        }

      (height, lastBlockHash)
    }
  }

  def getNativeTransfers(fromIndex: Long, maxItems: Long): Vector[ContractNativeTransfer] =
    if (maxItems == 0) Vector.empty
    else (fromIndex until math.min(fromIndex + maxItems, getNativeTransfersCount)).map(requireNativeTransfer).toVector

  private def getNativeTransfersCount: Long = getLongData("nativeTransfersCount").getOrElse(0L)

  private def requireNativeTransfer(atIndex: Long): ContractNativeTransfer = {
    val key   = s"nativeTransfer_$atIndex"
    val raw   = getStringData(key).getOrElse(fail(s"Expected a native transfer at '$key', got nothing"))
    val parts = raw.split(Sep)
    if (parts.length != 2) fail(s"Expected two elements in a native transfer, got ${parts.length}: $raw")

    val destElAddress = EthAddress.unsafeFrom(parts(0))
    val amount        = parts(1).toLongOption.getOrElse(fail(s"Expected an integer amount of a native transfer, got: ${parts(1)}"))

    ContractNativeTransfer(atIndex, destElAddress, amount)
  }

  def getAssetTransfers(fromIndex: Long, maxItems: Long): Vector[ContractAssetTransfer] =
    if (maxItems == 0) Vector.empty
    else (fromIndex until math.min(fromIndex + maxItems, getAssetTransfersCount)).map(requireAssetTransfer).toVector

  private def requireAssetTransfer(atIndex: Long): ContractAssetTransfer = {
    val key   = s"assetTransfer_$atIndex"
    val raw   = getStringData(key).getOrElse(fail(s"Expected an asset transfer at '$key', got nothing"))
    val parts = raw.split(Sep)
    if (parts.length != 3) fail(s"Expected 3 elements in asset transfer, got ${parts.length}: $raw")

    val destElAddress = EthAddress.unsafeFrom(parts(0))
    val amount        = parts(1).toLongOption.getOrElse(fail(s"Expected an integer amount of asset transfer, got: ${parts(1)}"))
    val assetIndex    = parts(2).toIntOption.getOrElse(fail(s"Expected an asset index in asset transfer, got: ${parts(2)}"))

    val asset     = getRegisteredAsset(assetIndex)
    val assetData = getRegisteredAssetData(asset)

    ContractAssetTransfer(atIndex, destElAddress, amount, assetData.erc20Address)
  }

  private def getAssetTransfersCount: Long = getLongData("assetTransfersCount").getOrElse(0L)

  private def getRegisteredAssetData(asset: Asset): Registry.RegisteredAsset = {
    val key   = s"assetRegistry_${Registry.stringifyAsset(asset)}"
    val raw   = getStringData(key).getOrElse(fail(s"Can't find a registered asset $asset at $key"))
    val parts = raw.split(Sep)
    if (parts.length < 3) fail(s"Expected at least 3 elements in $key, got ${parts.length}: $raw")

    val assetIndex   = parts(0).toIntOption.getOrElse(fail(s"Expected an index of asset at $key(0), got: ${parts(1)}"))
    val erc20Address = EthAddress.unsafeFrom(s"0x${parts(1)}")
    val exponent     = parts(2).toIntOption.getOrElse(fail(s"Expected an exponent of asset at $key(2), got: ${parts(2)}"))

    Registry.RegisteredAsset(asset, assetIndex, erc20Address, exponent)
  }

  def getAssetRegistrySize: Int = getLongData("assetRegistrySize").getOrElse(0L).toInt

  def getAllRegisteredAssets: List[Registry.RegisteredAsset] = getRegisteredAssets(0 until getAssetRegistrySize)

  def getRegisteredAssets(indexes: Range): List[Registry.RegisteredAsset] =
    indexes.view
      .map(getRegisteredAsset)
      .map(getRegisteredAssetData)
      .toList

  private def getRegisteredAsset(registryIndex: Int): Asset =
    getStringData(s"assetRegistryIndex_$registryIndex") match {
      case None          => fail(s"Can't find a registered asset at $registryIndex")
      case Some(assetId) => Registry.parseAsset(assetId)
    }

  private def getLastBlockHash(chainId: Long): Option[BlockHash] = getChainMeta(chainId).map(_._2)

  protected def getFirstBlockHash(chainId: Long): Option[BlockHash] =
    getBlockHash(s"chain${chainId}FirstBlock")

  protected def getBinaryData(key: String): Option[ByteStr] =
    extractBinaryValue(key, extractData(key))

  protected def getStringData(key: String): Option[String] =
    extractStringValue(key, extractData(key))

  protected def getLongData(key: String): Option[Long] =
    extractLongValue(key, extractData(key))

  private def extractLongValue(context: String, extractedDataEntry: Option[DataEntry[?]]): Option[Long] =
    extractValue[IntegerDataEntry](context, extractedDataEntry).map(_.value)

  private def extractBinaryValue(context: String, extractedDataEntry: Option[DataEntry[?]]): Option[ByteStr] =
    extractValue[BinaryDataEntry](context, extractedDataEntry).map(_.value)

  private def extractStringValue(context: String, extractedDataEntry: Option[DataEntry[?]]): Option[String] =
    extractValue[StringDataEntry](context, extractedDataEntry).map(_.value)

  private def extractValue[T <: DataEntry[?]](context: String, x: Option[DataEntry[?]])(implicit ct: ClassTag[T]): Option[T] = x match {
    case Some(x: T)              => Some(x)
    case Some(EmptyDataEntry(_)) => None
    case Some(x)                 => fail(s"$context: expected ${ct.runtimeClass.getSimpleName}, got: $x")
    case None                    => None
  }

  private def clean(hash: BlockHash): String = hash.drop(2) // Drop "0x"
}

object ChainContractClient {
  val MinMinerBalance: Long = 20000_00000000L
  val DefaultMainChainId    = 0L

  private val AllMinersKey       = "allMiners"
  private val MainChainIdKey     = "mainChainId"
  private val BlockHashBytesSize = 32
  private val Sep                = ","

  val MaxC2ENativeTransfers = 16
  val MaxC2EAssetTransfers  = Int.MaxValue // TODO: Change after testing

  val NativeTransfersFlag = 1
  val AssetTransfersFlag  = 2

  private class InconsistentContractData(message: String, cause: Throwable = null)
      extends IllegalStateException(s"Probably, your have to upgrade your client. $message", cause)

  case class EpochContractMeta(miner: Address, prevEpoch: Int, lastBlockHash: BlockHash)

  case class ContractNativeTransfer(index: Long, destElAddress: EthAddress, amount: Long)

  case class ContractAssetTransfer(index: Long, destElAddress: EthAddress, amount: Long, erc20Address: EthAddress)

  object Registry {
    val WavesAssetName = "WAVES"

    case class RegisteredAsset(asset: Asset, index: Int, erc20Address: EthAddress, exponent: Int) {
      override def toString: String = s"RegisteredAsset($asset, i=$index, $erc20Address, e=$exponent)"
    }

    def parseAsset(rawAssetId: String): Asset =
      if (rawAssetId == WavesAssetName) Asset.Waves
      else Asset.IssuedAsset(ByteStr.decodeBase58(rawAssetId).getOrElse(fail(s"Can't decode an asset id: $rawAssetId")))

    def stringifyAsset(asset: Asset): String = asset.fold(WavesAssetName)(_.id.toString)
  }

  private def fail(reason: String, cause: Throwable = null): Nothing = throw new InconsistentContractData(reason, cause)
}
