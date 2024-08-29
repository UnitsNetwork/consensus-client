package units.client.contract

import cats.implicits.*
import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.block.BlockHeader
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.consensus.{FairPoSCalculator, PoSCalculator}
import units.BlockHash
import units.client.contract.ChainContractClient.*
import units.client.staking.StakingContractClient
import units.eth.{EthAddress, Gwei}
import units.util.HexBytesConverter
import units.util.HexBytesConverter.*
import com.wavesplatform.lang.Global
import com.wavesplatform.serialization.ByteBufferOps
import com.wavesplatform.state.{BinaryDataEntry, Blockchain, DataEntry, EmptyDataEntry, IntegerDataEntry, StringDataEntry}

import java.nio.ByteBuffer
import scala.reflect.ClassTag

trait ChainContractClient {
  def contract: Address

  def extractData(key: String): Option[DataEntry[?]]

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

  def getL2RewardAddress(miner: Address): Option[EthAddress] = getElRewardAddress(ByteStr(miner.bytes))
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
        val clGenerator = ByteStr(bb.getByteArray(Address.AddressLength))
        val chainId     = if (bb.remaining() >= 8) bb.getLong() else 0L

        val elToClTransfersRootHash =
          if (bb.remaining() >= ContractBlock.ElToClTransfersRootHashLength) bb.getByteArray(ContractBlock.ElToClTransfersRootHashLength)
          else Array.emptyByteArray

        val lastClToElTransferIndex = if (bb.remaining() >= 8) bb.getLong() else -1L

        require(
          !bb.hasRemaining,
          s"Not parsed ${bb.remaining()} bytes from ${blockMeta.base64}, read data: " +
            s"chainHeight=$chainHeight, epoch=$epoch, parentHash=$parentHash, clGenerator=$clGenerator, chainId=$chainId, " +
            s"elToClTransfersRootHash=${HexBytesConverter.toHex(elToClTransfersRootHash)}, lastClToElTransferIndex=$lastClToElTransferIndex"
        )

        val minerRewardElAddress =
          if (chainHeight == 0) EthAddress.empty
          else getElRewardAddress(clGenerator).getOrElse(fail(s"Can't find a reward address for generator $clGenerator"))

        ContractBlock(
          hash,
          parentHash,
          epoch,
          chainHeight,
          clGenerator,
          minerRewardElAddress,
          chainId,
          elToClTransfersRootHash,
          lastClToElTransferIndex
        )
      } catch {
        case e: Throwable => fail(s"Can't read a block $hash meta, bytes: ${blockMeta.base64}, remaining: ${bb.remaining()}", e)
      }
    }

  def getLastChainId: Long =
    getLongData("lastChainId").getOrElse(DefaultMainChainId)

  def getFirstValidAltChainId: Long =
    getLongData("firstValidAltChainId").getOrElse(DefaultMainChainId)

  def getStakingContractAddress: Option[Address] =
    extractData(StakingContractAddressKey)
      .collect {
        case StringDataEntry(_, v)  => Address.fromString(v)
        case BinaryDataEntry(_, bs) => Address.fromBytes(bs.arr)
      }
      .flatMap(_.toOption)

  def getMainChainIdOpt: Option[Long] =
    getLongData(MainChainIdKey)

  def getMainChainId: Long =
    getMainChainIdOpt.getOrElse(DefaultMainChainId)

  def getGeneratorChainId(generator: ByteStr): Long =
    getLongData(s"chainIdOf${toHex(generator)}").getOrElse(DefaultMainChainId)

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
      stakingContractAddress: Address,
      blockchain: Blockchain
  ): Option[(Address, Long)] = {
    val hit = Global.blake2b256(hitSource ++ miner.bytes).take(PoSCalculator.HitSize)
    val l2mpBalance = new StakingContractClient(blockchain.accountData(stakingContractAddress, _))
      .getL2mpBalance(miner, blockchain.height)

    if (blockchain.generatingBalance(miner) >= MinMinerBalance && l2mpBalance > 0) {
      // See WavesEnvironment.calculateDelay
      val delay = FairPoSCalculator(0, 0).calculateDelay(BigInt(1, hit), baseTarget, l2mpBalance)
      Some(miner -> delay)
    } else None
  }

  def calculateEpochMiner(header: BlockHeader, hitSource: ByteStr, epochNumber: Int, blockchain: Blockchain): Either[String, Address] =
    for {
      stakingContractAddress <- getStakingContractAddress.toRight("Staking contract address is not defined")
      bestMiner <- getAllActualMiners
        .flatMap(miner => calculateMinerDelay(hitSource.arr, header.baseTarget, miner, stakingContractAddress, blockchain))
        .minByOption(_._2)
        .map(_._1)
        .toRight(s"No miner for epoch $epochNumber")
    } yield bestMiner

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
    elBridgeAddress = getStringData("elBridgeAddress")
      .map(EthAddress.unsafeFrom)
      .getOrElse(throw new IllegalStateException("elBridgeAddress is empty on contract"))
  )

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

  def getNativeTransfers(fromIndex: Long, maxItems: Long): Vector[ContractTransfer] =
    (fromIndex until math.min(fromIndex + maxItems, getNativeTransfersCount)).map(requireNativeTransfer).toVector

  private def getNativeTransfersCount: Long = getLongData("nativeTransfersCount").getOrElse(0L)

  private def requireNativeTransfer(atIndex: Long): ContractTransfer = {
    val key   = s"nativeTransfer_$atIndex"
    val raw   = getStringData(key).getOrElse(fail(s"Expected a native transfer at '$key', got nothing"))
    val parts = raw.split(Sep)
    if (parts.length != 2) fail(s"Expected two elements in a native transfer, got ${parts.length}: $raw")

    val destElAddress = EthAddress.unsafeFrom(parts(0))
    val amount        = parts(1).toLongOption.getOrElse(fail(s"Expected an integer amount of a native transfer, got: ${parts(1)}"))

    ContractTransfer(atIndex, destElAddress, amount)
  }

  private def getLastBlockHash(chainId: Long): Option[BlockHash] = getChainMeta(chainId).map(_._2)

  private def getFirstBlockHash(chainId: Long): Option[BlockHash] =
    getBlockHash(s"chain${chainId}FirstBlock")

  private def getBinaryData(key: String): Option[ByteStr] =
    extractBinaryValue(key, extractData(key))

  private def getStringData(key: String): Option[String] =
    extractStringValue(key, extractData(key))

  private def getLongData(key: String): Option[Long] =
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

  private def fail(reason: String, cause: Throwable = null): Nothing = throw new InconsistentContractData(reason, cause)
}

object ChainContractClient {
  val MinMinerBalance: Long = 20000_00000000L
  val DefaultMainChainId    = 0

  private val AllMinersKey              = "allMiners"
  private val MainChainIdKey            = "mainChainId"
  private val StakingContractAddressKey = "stakingContractAddress"
  private val BlockHashBytesSize        = 32
  private val Sep                       = ","

  val MaxClToElTransfers = 16

  private class InconsistentContractData(message: String, cause: Throwable = null)
      extends IllegalStateException(s"Probably, your have to upgrade your client. $message", cause)

  case class EpochContractMeta(miner: Address, prevEpoch: Int, lastBlockHash: BlockHash)

  case class ContractTransfer(index: Long, destElAddress: EthAddress, amount: Long)
}
