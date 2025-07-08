package units.client.contract

import cats.implicits.*
import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.consensus.{FairPoSCalculator, PoSCalculator}
import com.wavesplatform.lang.Global
import com.wavesplatform.serialization.ByteBufferOps
import com.wavesplatform.state.{BinaryDataEntry, Blockchain, BooleanDataEntry, DataEntry, EmptyDataEntry, IntegerDataEntry, StringDataEntry}
import com.wavesplatform.transaction.Asset
import units.ELUpdater.EpochInfo
import units.client.contract.ChainContractClient.*
import units.eth.{EthAddress, Gwei}
import units.util.HexBytesConverter
import units.{BlockHash, EAmount, WAmount, scale}

import java.nio.ByteBuffer
import scala.annotation.tailrec
import scala.reflect.ClassTag

trait ChainContractClient {
  def contract: Address

  def extractData(key: String): Option[DataEntry[?]]

  def isContractSetup: Boolean = getLongData("minerReward").isDefined

  def isStopped: Boolean = getBooleanData("stopped").getOrElse(false)

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
      .filter(_.nonEmpty)
      .flatMap(_.split(Sep))
      .map { x =>
        Address.fromString(x) match {
          case Left(e)  => fail(s"Can't parse $x as Address: $e")
          case Right(x) => x
        }
      }

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

        val e2cTransfersRootHash =
          if (bb.remaining() >= ContractBlock.E2CTransfersRootHashLength) bb.getByteArray(ContractBlock.E2CTransfersRootHashLength)
          else Array.emptyByteArray

        val lastC2ETransferIndex   = bb.getLong()
        val lastAssetRegistryIndex = if (bb.remaining() >= 8) bb.getLong() else -1L

        require(
          !bb.hasRemaining,
          s"Not parsed ${bb.remaining()} bytes from ${blockMeta.base64}, read data: " +
            s"chainHeight=$chainHeight, epoch=$epoch, parentHash=$parentHash, chainId=$chainId, " +
            s"e2cTransfersRootHash=${HexBytesConverter.toHex(e2cTransfersRootHash)}, lastC2ETransferIndex=$lastC2ETransferIndex" +
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
          e2cTransfersRootHash,
          lastC2ETransferIndex,
          if (lastAssetRegistryIndex.isValidInt) lastAssetRegistryIndex.toInt
          else fail(s"$lastAssetRegistryIndex is not a valid int")
        )
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

  private def calculateEpochMiner(epochNumber: Int, blockchain: Blockchain): Either[String, Address] =
    for {
      header    <- blockchain.blockHeader(epochNumber).toRight(s"No header at height $epochNumber")
      hitSource <- blockchain.hitSource(epochNumber).toRight(s"No VRF value at height $epochNumber")
      miner <- getAllActualMiners
        .flatMap(miner => calculateMinerDelay(hitSource.arr, header.header.baseTarget, miner, blockchain))
        .minByOption(_._2)
        .map(_._1)
        .toRight(s"No miner for epoch $epochNumber")
    } yield miner

  def calculateEpochInfo(blockchain: Blockchain): Either[String, EpochInfo] = {
    val epochNumber = blockchain.height
    for {
      _                      <- blockchain.blockHeader(epochNumber).toRight(s"No header at epoch $epochNumber")
      hitSource              <- blockchain.hitSource(epochNumber).toRight(s"No hit source at epoch $epochNumber")
      miner                  <- this.calculateEpochMiner(epochNumber, blockchain)
      rewardAddress          <- this.getElRewardAddress(miner).toRight(s"No reward address for $miner")
      prevEpochLastBlockHash <- this.getPrevEpochLastBlockHash(epochNumber)
    } yield EpochInfo(epochNumber, miner, rewardAddress, hitSource, prevEpochLastBlockHash)
  }

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
      .map(EthAddress.unsafeFrom),
    assetTransfersActivationEpoch = getAssetTransfersActivationEpoch
  )

  private def getAssetTransfersActivationEpoch: Long = getLongData("assetTransfersActivationEpoch").getOrElse(Long.MaxValue)

  def getStrictC2ETransfersActivationEpoch: Long = getLongData("strictC2ETransfersActivationEpoch").getOrElse(Long.MaxValue)

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

  def getTransfersForPayload(fromIndex: Long, maxNative: Long): Vector[ContractTransfer] = {
    val maxIndex = getTransfersCount - 1

    @tailrec def loop(currIndex: Long, foundNative: Long, acc: Vector[ContractTransfer]): Vector[ContractTransfer] =
      if (currIndex > maxIndex) acc
      else
        requireTransfer(currIndex) match {
          case x: (ContractTransfer.NativeViaWithdrawal | ContractTransfer.NativeViaDeposit) =>
            val updatedFoundNative = foundNative + 1
            if (updatedFoundNative > maxNative) acc
            else loop(currIndex + 1, updatedFoundNative, acc :+ x) // if equals - we still can collect asset transfers

          case x: ContractTransfer.Asset => loop(currIndex + 1, foundNative, acc :+ x)
        }

    loop(fromIndex, 0, Vector.empty)
  }

  def getTransfers(fromIndex: Long, max: Long): Vector[ContractTransfer] =
    if (max == 0) Vector.empty
    else (fromIndex until math.min(fromIndex + max, getTransfersCount)).map(requireTransfer).to(Vector)

  private def getTransfersCount: Long = getLongData("nativeTransfersCount").getOrElse(0L)

  private def requireTransfer(atIndex: Long): ContractTransfer = {
    val key = s"nativeTransfer_$atIndex"
    val raw = getStringData(key).getOrElse(fail(s"Expected a transfer at '$key', got nothing"))
    val xs  = raw.split(Sep)
    xs match {
      // Native transfer, before strict transfers activation
      // {destElAddressHex with 0x}_{amount}
      case Array(rawDestElAddress, rawAmount) =>
        ContractTransfer.NativeViaWithdrawal(
          index = atIndex,
          epoch = 0,
          to = EthAddress.unsafeFrom(rawDestElAddress),
          amount = rawAmount.toLongOption.getOrElse(fail(s"Expected an integer amount of a native transfer, got: ${rawAmount}"))
        )

      // Native transfer, after strict transfers activation
      // {epoch}_{destElAddressHex with 0x}_{fromClAddressHex with 0x}_{amount}
      case Array(rawEpoch, rawDestElAddress, rawFromAddress, rawAmount) if EthAddress.from(rawEpoch).isLeft =>
        ContractTransfer.NativeViaDeposit(
          index = atIndex,
          epoch = rawEpoch.toIntOption.getOrElse(fail(s"Expected an integer epoch, got: ${rawEpoch}")),
          from = EthAddress.unsafeFrom(rawFromAddress),
          to = EthAddress.unsafeFrom(rawDestElAddress),
          amount = rawAmount.toLongOption.getOrElse(fail(s"Expected an integer amount of a native transfer, got: ${rawAmount}"))
        )

      // Asset transfer, before strict transfers activation
      // {destElAddressHex with 0x}_{fromClAddressHex with 0x}_{amount}_{assetRegistryIndex}
      case Array(rawDestElAddress, rawFromAddress, rawAmount, rawAssetIndex) if EthAddress.from(rawDestElAddress).isRight => {
        val assetIndex = rawAssetIndex.toIntOption.getOrElse(fail(s"Expected an asset index in asset transfer, got: ${rawAssetIndex}"))
        val asset      = getRegisteredAsset(assetIndex)
        val assetData  = getRegisteredAssetData(asset)

        ContractTransfer.Asset(
          index = atIndex,
          epoch = 0,
          from = EthAddress.unsafeFrom(rawFromAddress),
          to = EthAddress.unsafeFrom(rawDestElAddress),
          amount =
            try WAmount(rawAmount).scale(assetData.exponent)
            catch { case e: ArithmeticException => fail(s"Expected an integer amount of a native transfer, got: ${rawAmount}", e) },
          tokenAddress = assetData.erc20Address,
          asset
        )
      }

      // Asset transfer, after strict transfers activation
      // {epoch}_{destElAddressHex with 0x}_{fromClAddressHex with 0x}_{amount}_{assetRegistryIndex}
      case Array(rawEpoch, rawDestElAddress, rawFromAddress, rawAmount, rawAssetIndex) => {
        val assetIndex = rawAssetIndex.toIntOption.getOrElse(fail(s"Expected an asset index in asset transfer, got: ${rawAssetIndex}"))
        val asset      = getRegisteredAsset(assetIndex)
        val assetData  = getRegisteredAssetData(asset)

        ContractTransfer.Asset(
          index = atIndex,
          epoch = rawEpoch.toIntOption.getOrElse(fail(s"Expected an integer epoch, got: ${rawEpoch}")),
          from = EthAddress.unsafeFrom(rawFromAddress),
          to = EthAddress.unsafeFrom(rawDestElAddress),
          amount =
            try WAmount(rawAmount).scale(assetData.exponent)
            catch { case e: ArithmeticException => fail(s"Expected an integer amount of a native transfer, got: ${rawAmount}", e) },
          tokenAddress = assetData.erc20Address,
          asset
        )
      }

      case _ => fail(s"Unexpected number of elements in a transfer key '$key', got ${xs.length}: $raw")
    }
  }

  def getRegisteredAssetData(asset: Asset): Registry.RegisteredAsset = {
    val key   = s"assetRegistry_${Registry.stringifyAsset(asset)}"
    val raw   = getStringData(key).getOrElse(fail(s"Can't find a registered asset $asset at $key"))
    val parts = raw.split(Sep)
    if (parts.length < 3) fail(s"Expected at least 3 elements in $key, got ${parts.length}: $raw")

    val assetIndex   = parts(0).toIntOption.getOrElse(fail(s"Expected an index of asset at $key(0), got: ${parts(1)}"))
    val erc20Address = EthAddress.unsafeFrom(parts(1))
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

  def getPrevEpochLastBlockHash(thisEpoch: Int): Either[String, Option[BlockHash]] = {
    @tailrec
    def loop(curEpochNumber: Int): Either[String, Option[BlockHash]] = {
      if (curEpochNumber <= 0) {
        Left(s"Couldn't find previous epoch meta for epoch #$thisEpoch")
      } else {
        this.getEpochMeta(curEpochNumber) match {
          case Some(epochMeta) => Right(Some(epochMeta.lastBlockHash))
          case _               => loop(curEpochNumber - 1)
        }
      }
    }

    this.getEpochMeta(thisEpoch) match {
      case Some(epochMeta) if epochMeta.prevEpoch == 0 =>
        Right(None)
      case Some(epochMeta) =>
        this
          .getEpochMeta(epochMeta.prevEpoch)
          .toRight(s"Epoch #${epochMeta.prevEpoch} meta not found at contract")
          .map(em => Some(em.lastBlockHash))
      case _ => loop(thisEpoch - 1)
    }
  }

  def getRegisteredAsset(registryIndex: Int): Asset =
    getStringData(s"assetRegistryIndex_$registryIndex") match {
      case None          => fail(s"Can't find a registered asset at $registryIndex")
      case Some(assetId) => Registry.parseAsset(assetId)
    }

  def findAltChain(prevChainId: Long, referenceBlock: BlockHash): Option[ChainInfo] = {
    val lastChainId          = this.getLastChainId
    val firstValidAltChainId = this.getFirstValidAltChainId

    (firstValidAltChainId.max(prevChainId + 1) to lastChainId).foldLeft(Option.empty[ChainInfo]) {
      case (Some(chainInfo), _) => Some(chainInfo)
      case (_, chainId) =>
        val chainInfo = this.getChainInfo(chainId)
        val isNeededAltChain = chainInfo.exists { chainInfo =>
          !chainInfo.isMain && chainInfo.firstBlock.parentHash == referenceBlock
        }
        if (isNeededAltChain) chainInfo else None
    }
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

  protected def getBooleanData(key: String): Option[Boolean] =
    extractBooleanValue(key, extractData(key))

  private def extractLongValue(context: String, extractedDataEntry: Option[DataEntry[?]]): Option[Long] =
    extractValue[IntegerDataEntry](context, extractedDataEntry).map(_.value)

  private def extractBinaryValue(context: String, extractedDataEntry: Option[DataEntry[?]]): Option[ByteStr] =
    extractValue[BinaryDataEntry](context, extractedDataEntry).map(_.value)

  private def extractStringValue(context: String, extractedDataEntry: Option[DataEntry[?]]): Option[String] =
    extractValue[StringDataEntry](context, extractedDataEntry).map(_.value)

  private def extractBooleanValue(context: String, extractedDataEntry: Option[DataEntry[?]]): Option[Boolean] =
    extractValue[BooleanDataEntry](context, extractedDataEntry).map(_.value)

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
  val Sep                        = ","

  private class InconsistentContractData(message: String, cause: Throwable = null)
      extends IllegalStateException(s"Probably, you have to upgrade your client. $message", cause)

  case class EpochContractMeta(miner: Address, prevEpoch: Int, lastBlockHash: BlockHash)

  enum ContractTransfer {
    val index: Long
    val epoch: Int

    case NativeViaWithdrawal(index: Long, epoch: Int, to: EthAddress, amount: Long)
    case NativeViaDeposit(index: Long, epoch: Int, from: EthAddress, to: EthAddress, amount: Long)
    case Asset(
        index: Long,
        epoch: Int,
        from: EthAddress,
        to: EthAddress,
        amount: EAmount,
        tokenAddress: EthAddress,
        asset: com.wavesplatform.transaction.Asset
    )
  }

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
