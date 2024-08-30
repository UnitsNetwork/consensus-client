package units

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.typesafe.scalalogging.StrictLogging
import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.merkle.Digest
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.crypto
import units.ELUpdater.State.*
import units.ELUpdater.State.ChainStatus.{FollowingChain, Mining, WaitForNewChain}
import units.client.L2BlockLike
import units.client.contract.*
import units.client.engine.EngineApiClient
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.client.engine.model.Withdrawal.WithdrawalIndex
import units.client.http.EcApiClient
import units.client.http.model.EcBlock
import units.eth.{EmptyL2Block, EthAddress, EthereumConstants}
import units.network.BlocksObserverImpl.BlockWithChannel
import units.util.HexBytesConverter
import units.util.HexBytesConverter.toHexNoPrefix
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms.{CONST_LONG, CONST_STRING, FUNCTION_CALL}
import com.wavesplatform.network.ChannelGroupExt
import com.wavesplatform.state.Blockchain
import com.wavesplatform.state.diffs.FeeValidation.{FeeConstants, FeeUnit, ScriptExtraFee}
import com.wavesplatform.state.diffs.TransactionDiffer.TransactionValidationError
import com.wavesplatform.transaction.TxValidationError.InvokeRejectError
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.smart.script.trace.TracedResult
import com.wavesplatform.transaction.{Asset, Proofs, Transaction, TransactionSignOps, TransactionType, TxPositiveAmount, TxVersion}
import com.wavesplatform.utils.{EthEncoding, Time, UnsupportedFeature, forceStopApplication}
import com.wavesplatform.utx.UtxPool
import com.wavesplatform.wallet.Wallet
import io.netty.channel.Channel
import io.netty.channel.group.DefaultChannelGroup
import monix.execution.cancelables.SerialCancelable
import monix.execution.{CancelableFuture, Scheduler}
import play.api.libs.json.*

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.*

class ELUpdater(
    httpApiClient: EcApiClient,
    engineApiClient: EngineApiClient,
    blockchain: Blockchain,
    utx: UtxPool,
    allChannels: DefaultChannelGroup,
    config: ClientConfig,
    time: Time,
    wallet: Wallet,
    requestBlockFromPeers: BlockHash => CancelableFuture[BlockWithChannel],
    broadcastTx: Transaction => TracedResult[ValidationError, Boolean],
    scheduler: Scheduler,
    globalScheduler: Scheduler
) extends StrictLogging
    with AutoCloseable {
  import ELUpdater.*

  private val handleNextUpdate    = SerialCancelable()
  private val contractAddress     = config.chainContractAddress
  private val chainContractClient = new ChainContractStateClient(contractAddress, blockchain)

  private[units] var state: State = Starting

  def consensusLayerChanged(): Unit =
    handleNextUpdate := scheduler.scheduleOnce(ClChangedProcessingDelay)(handleConsensusLayerChanged())

  def executionBlockReceived(block: NetworkL2Block, ch: Channel): Unit = scheduler.execute { () =>
    logger.debug(s"New block ${block.hash}->${block.parentHash} (timestamp=${block.timestamp}, height=${block.height}) appeared")

    state match {
      case WaitingForSyncHead(target, _) if block.hash == target.hash =>
        val syncStarted = for {
          _         <- engineApiClient.applyNewPayload(block.payload)
          fcuStatus <- confirmBlock(target, target)
        } yield fcuStatus

        syncStarted match {
          case Left(value) =>
            logger.error(s"Error starting sync: $value")
            setState("1", Starting)
          case Right(fcuStatus) =>
            setState("2", SyncingToFinalizedBlock(target.hash))
            logger.debug(s"Waiting for sync completion: $fcuStatus")
            waitForSyncCompletion(target)
        }
      case w @ Working(_, lastEcBlock, _, _, _, FollowingChain(nodeChainInfo, _), _, returnToMainChainInfo) if block.parentHash == lastEcBlock.hash =>
        validateAndApply(block, ch, w, lastEcBlock, nodeChainInfo, returnToMainChainInfo, ignoreInvalid = !canSupportAnotherAltChain(nodeChainInfo))
      case w: Working[?] =>
        w.returnToMainChainInfo match {
          case Some(rInfo) if rInfo.missedEcBlockHash == block.hash =>
            chainContractClient.getChainInfo(rInfo.chainId) match {
              case Some(chainInfo) if chainInfo.isMain =>
                validateAndApply(block, ch, w, rInfo.missedBlockParent, chainInfo, None, ignoreInvalid = true)
              case Some(_) =>
                logger.debug(s"Chain ${rInfo.chainId} is not main anymore, ignoring block ${block.hash}")
              case _ =>
                logger.error(s"Failed to get chain ${rInfo.chainId} info, ignoring block ${block.hash}")
            }
          case _ => logger.debug(s"$w: ignoring block ${block.hash}")
        }
      case other =>
        logger.debug(s"$other: ignoring block ${block.hash}")
    }
  }

  override def close(): Unit = {}

  private def calculateEpochInfo: Either[String, EpochInfo] = {
    val epochNumber = blockchain.height
    for {
      header                 <- blockchain.blockHeader(epochNumber).toRight(s"No header at epoch $epochNumber")
      hitSource              <- blockchain.hitSource(epochNumber).toRight(s"No hit source at epoch $epochNumber")
      miner                  <- chainContractClient.calculateEpochMiner(header.header, hitSource, epochNumber, blockchain)
      rewardAddress          <- chainContractClient.getL2RewardAddress(miner).toRight(s"No reward address for $miner")
      prevEpochLastBlockHash <- getPrevEpochLastBlockHash(epochNumber)
    } yield EpochInfo(epochNumber, miner, rewardAddress, hitSource, prevEpochLastBlockHash)
  }

  private def getPrevEpochLastBlockHash(startEpochNumber: Int): Either[String, Option[BlockHash]] = {
    @tailrec
    def loop(curEpochNumber: Int): Either[String, Option[BlockHash]] = {
      if (curEpochNumber <= 0) {
        Left(s"Couldn't find previous epoch meta for epoch #$startEpochNumber")
      } else {
        chainContractClient.getEpochMeta(curEpochNumber) match {
          case Some(epochMeta) => Right(Some(epochMeta.lastBlockHash))
          case _               => loop(curEpochNumber - 1)
        }
      }
    }

    chainContractClient.getEpochMeta(startEpochNumber) match {
      case Some(epochMeta) if epochMeta.prevEpoch == 0 =>
        Right(None)
      case Some(epochMeta) =>
        chainContractClient
          .getEpochMeta(epochMeta.prevEpoch)
          .toRight(s"Epoch #${epochMeta.prevEpoch} meta not found at contract")
          .map(em => Some(em.lastBlockHash))
      case _ => loop(startEpochNumber - 1)
    }
  }

  private def cleanPriorityPool(): Unit = {
    // A transaction moves to priority pool when a new key block references one of the previous micro blocks.
    // When we add a new fresh transaction (extendMainChain) to UTX, it is validated against a stale transaction changes.
    // Removing here, because we have these transactions in PP after the onProcessBlock trigger
    utx.getPriorityPool.foreach { pp =>
      val staleTxs = pp.priorityTransactions.filter {
        case tx: InvokeScriptTransaction => tx.dApp == contractAddress && ContractFunction.AllNames.contains(tx.funcCall.function.funcName)
        case _                           => false
      }

      if (staleTxs.nonEmpty) {
        logger.debug(s"Removing stale transactions: ${staleTxs.map(_.id()).mkString(", ")}")
        utx.removeAll(staleTxs)
      }
    }
  }

  private def callContract(
      contractFunction: ContractFunction,
      blockData: EcBlock,
      transfersRootHash: Digest,
      lastClToElTransferIndex: Long,
      invoker: KeyPair
  ): Job[Unit] = {
    val extraFee = if (blockchain.hasPaidVerifier(invoker.toAddress)) ScriptExtraFee else 0

    val chainIdArg = contractFunction.chainIdOpt.map(CONST_LONG.apply).toList
    val epochArg   = contractFunction.epochOpt.map(x => CONST_LONG(x)).toList

    val v2Args =
      if (contractFunction.version >= ContractFunction.V2) List(CONST_STRING(toHexNoPrefix(transfersRootHash)).explicitGet())
      else Nil

    val v3Args =
      if (contractFunction.version >= ContractFunction.V3) List(CONST_LONG(lastClToElTransferIndex))
      else Nil

    val args = chainIdArg ++ List(
      CONST_STRING(toHexNoPrefix(blockData.hashByteStr.arr)).explicitGet(),
      CONST_STRING(toHexNoPrefix(blockData.parentHashByteStr.arr)).explicitGet()
    ) ++ epochArg ++ v2Args ++ v3Args
    val tx = InvokeScriptTransaction(
      TxVersion.V2,
      invoker.publicKey,
      contractAddress,
      Some(
        FUNCTION_CALL(
          FunctionHeader.User(contractFunction.name),
          args
        )
      ),
      Seq.empty,
      TxPositiveAmount.unsafeFrom(FeeConstants(TransactionType.InvokeScript) * FeeUnit + extraFee),
      Asset.Waves,
      System.currentTimeMillis(),
      Proofs.empty,
      blockchain.settings.addressSchemeCharacter.toByte
    ).signWith(invoker.privateKey)
    logger.info(s"Invoking $contractAddress '${contractFunction.name}' for block ${blockData.hash}->${blockData.parentHash}, txId=${tx.id()}")
    cleanPriorityPool()

    broadcastTx(tx).resultE match {
      case Right(true)  => Either.unit
      case Right(false) => logger.warn(s"Transaction ${tx.id()} is old! Contact with developers").asRight
      case Left(TransactionValidationError(InvokeRejectError(message, _), _)) =>
        val fatalReasonMessage =
          if (message.contains("Upgrade your client")) Some(message)
          else if (message.contains("doesn't exist in the script")) Some(s"$message. Upgrade your client")
          else None

        fatalReasonMessage.foreach { fatalReasonMessage =>
          logger.error(fatalReasonMessage)
          forceStopApplication(UnsupportedFeature)
        }
        ClientError(s"Failed tx=${tx.id()}: $message").asLeft

      case Left(e) => ClientError(s"Failed tx=${tx.id()}: ${e.toString}").asLeft
    }
  }

  private def prepareAndApplyPayload(
      payloadId: PayloadId,
      referenceHash: BlockHash,
      timestamp: Long,
      contractFunction: ContractFunction,
      chainContractOptions: ChainContractOptions
  ): Unit = {
    def getWaitingTime: Option[FiniteDuration] = {
      val timestampAheadTime = (timestamp - time.correctedTime() / 1000).max(0)
      if (timestampAheadTime > 0) {
        Some(timestampAheadTime.seconds)
      } else if (!chainContractClient.blockExists(referenceHash)) {
        Some(WaitForReferenceConfirmInterval)
      } else None
    }

    state match {
      case Working(epochInfo, _, _, _, _, m: Mining, _, _) if m.currentPayloadId == payloadId =>
        getWaitingTime match {
          case Some(waitingTime) =>
            scheduler.scheduleOnce(waitingTime)(
              prepareAndApplyPayload(payloadId, referenceHash, timestamp, contractFunction, chainContractOptions)
            )
          case _ =>
            (for {
              payload <- engineApiClient.getPayload(payloadId)
              _ = logger.info(s"Forged payload $payloadId")
              latestValidHashOpt <- engineApiClient.applyNewPayload(payload)
              latestValidHash    <- Either.fromOption(latestValidHashOpt, ClientError("Latest valid hash not defined"))
              _ = logger.info(s"Applied payload $payloadId, block hash is $latestValidHash, timestamp = $timestamp")
              newBlock <- NetworkL2Block.signed(payload, m.keyPair.privateKey)
              _ = logger.debug(s"Broadcasting block ${newBlock.hash}")
              _ <- Try(allChannels.broadcast(newBlock)).toEither.leftMap(err =>
                ClientError(s"Failed to broadcast block ${newBlock.hash}: ${err.toString}")
              )
              ecBlock = newBlock.toEcBlock
              transfersRootHash <- getElToClTransfersRootHash(ecBlock.hash, chainContractOptions.elBridgeAddress)
              _ <- callContract(
                contractFunction,
                ecBlock,
                transfersRootHash,
                m.lastClToElTransferIndex,
                m.keyPair
              )
            } yield ecBlock).fold(
              err => logger.error(s"Failed to forge block for payloadId $payloadId at epoch ${epochInfo.number}: ${err.message}"),
              newEcBlock => scheduler.execute { () => tryToForgeNextBlock(epochInfo.number, newEcBlock, chainContractOptions) }
            )
        }
      case other => logger.debug(s"Unexpected state $other attempting to finish building $payloadId")
    }
  }

  private def rollbackTo[CS <: ChainStatus](prevState: Working[CS], target: L2BlockLike, finalizedBlock: ContractBlock): Job[Working[CS]] = {
    val targetHash = target.hash
    for {
      rollbackBlock <- mkRollbackBlock(targetHash)
      _                   = logger.info(s"Starting rollback to $targetHash using rollback block ${rollbackBlock.hash}")
      fixedFinalizedBlock = if (finalizedBlock.height > rollbackBlock.parentBlock.height) rollbackBlock.parentBlock else finalizedBlock
      _           <- confirmBlock(rollbackBlock.hash, fixedFinalizedBlock.hash)
      _           <- confirmBlock(target, fixedFinalizedBlock)
      lastEcBlock <- httpApiClient.getLastExecutionBlock
      _ <- Either.cond(
        targetHash == lastEcBlock.hash,
        (),
        ClientError(s"Rollback to $targetHash error: last execution block ${lastEcBlock.hash} is not equal to target block hash")
      )
    } yield {
      logger.info(s"Rollback to $targetHash finished successfully")
      val newState =
        prevState.copy(lastEcBlock = lastEcBlock, fullValidationStatus = prevState.fullValidationStatus.copy(lastElWithdrawalIndex = None))
      setState("10", newState)
      newState
    }
  }

  private def validateRandao(networkBlock: NetworkL2Block, epochNumber: Int): Either[String, Unit] =
    blockchain.vrf(epochNumber) match {
      case None => s"VRF of $epochNumber epoch is empty".asLeft
      case Some(vrf) =>
        val expectedPrevRandao = calculateRandao(vrf, networkBlock.parentHash)
        Either.cond(
          expectedPrevRandao == networkBlock.prevRandao,
          (),
          s"expected prevRandao $expectedPrevRandao, got ${networkBlock.prevRandao}, VRF=$vrf of $epochNumber"
        )
    }

  private def startBuildingPayload(
      epochInfo: EpochInfo,
      parentBlock: EcBlock,
      finalizedBlock: ContractBlock,
      nextBlockUnixTs: Long,
      lastClToElTransferIndex: Long,
      lastElWithdrawalIndex: WithdrawalIndex,
      chainContractOptions: ChainContractOptions,
      prevEpochMinerRewardAddress: Option[EthAddress]
  ): Job[MiningData] = {
    val firstElWithdrawalIndex   = lastElWithdrawalIndex + 1
    val startClToElTransferIndex = lastClToElTransferIndex + 1

    val rewardWithdrawal = prevEpochMinerRewardAddress
      .map(Withdrawal(firstElWithdrawalIndex, _, chainContractOptions.miningReward))
      .toVector

    val transfers =
      chainContractClient
        .getNativeTransfers(
          fromIndex = startClToElTransferIndex,
          maxItems = ChainContractClient.MaxClToElTransfers - rewardWithdrawal.size
        )

    val transferWithdrawals = toWithdrawals(transfers, rewardWithdrawal.lastOption.fold(firstElWithdrawalIndex)(_.index + 1))

    val withdrawals = rewardWithdrawal ++ transferWithdrawals

    confirmBlockAndStartMining(
      parentBlock,
      finalizedBlock,
      nextBlockUnixTs,
      epochInfo.rewardAddress,
      calculateRandao(epochInfo.hitSource, parentBlock.hash),
      withdrawals
    ).map { payloadId =>
      logger.info(
        s"Starting to forge payload $payloadId by miner ${epochInfo.miner} at height ${parentBlock.height + 1} " +
          s"of epoch ${epochInfo.number} (ref=${parentBlock.hash}), ${withdrawals.size} withdrawals, ${transfers.size} transfers from $startClToElTransferIndex"
      )

      MiningData(payloadId, nextBlockUnixTs, transfers.lastOption.fold(lastClToElTransferIndex)(_.index), lastElWithdrawalIndex + withdrawals.size)
    }
  }

  private def tryToStartMining(prevState: Working[?], nodeChainInfo: Either[ChainSwitchInfo, ChainInfo]): Unit = {
    val parentBlock = prevState.lastEcBlock
    val epochInfo   = prevState.epochInfo

    wallet.privateKeyAccount(epochInfo.miner) match {
      case Right(keyPair) if config.miningEnable =>
        logger.trace(s"Designated miner in epoch ${epochInfo.number} is ${epochInfo.miner}, attempting to build payload")
        val refContractBlock = nodeChainInfo match {
          case Left(chainSwitchInfo) => chainSwitchInfo.referenceBlock
          case Right(chainInfo)      => chainInfo.lastBlock
        }
        val lastClToElTransferIndex = refContractBlock.lastClToElTransferIndex

        (for {
          elWithdrawalIndexBefore <-
            parentBlock.withdrawals.lastOption.map(_.index) match {
              case Some(r) => Right(r)
              case None =>
                if (parentBlock.height - 1 <= EthereumConstants.GenesisBlockHeight) Right(-1L)
                else getLastWithdrawalIndex(parentBlock.parentHash)
            }
          nextBlockUnixTs = (parentBlock.timestamp + config.blockDelay.toSeconds).max(time.correctedTime() / 1000 + config.blockDelay.toSeconds)
          miningData <- startBuildingPayload(
            epochInfo,
            parentBlock,
            prevState.finalizedBlock,
            nextBlockUnixTs,
            lastClToElTransferIndex,
            elWithdrawalIndexBefore,
            prevState.options,
            Option.unless(parentBlock.height == EthereumConstants.GenesisBlockHeight)(parentBlock.minerRewardL2Address)
          )
        } yield {
          val newState = prevState.copy(
            epochInfo = epochInfo,
            lastEcBlock = parentBlock,
            chainStatus = Mining(keyPair, miningData.payloadId, nodeChainInfo, miningData.lastClToElTransferIndex, miningData.lastElWithdrawalIndex)
          )

          setState("12", newState)
          scheduler.scheduleOnce((miningData.nextBlockUnixTs - time.correctedTime() / 1000).min(1).seconds)(
            prepareAndApplyPayload(
              miningData.payloadId,
              parentBlock.hash,
              miningData.nextBlockUnixTs,
              newState.options.startEpochChainFunction(epochInfo.number, nodeChainInfo.toOption),
              newState.options
            )
          )
        }).fold(
          err => logger.error(s"Error starting payload build process: ${err.message}"),
          _ => ()
        )
      case _ =>
        logger.trace(s"Designated miner in epoch ${epochInfo.number} is ${epochInfo.miner}")
    }
  }

  private def tryToForgeNextBlock(
      epochNumber: Int,
      parentBlock: EcBlock,
      chainContractOptions: ChainContractOptions
  ): Unit = {
    state match {
      case w @ Working(epochInfo, _, finalizedBlock, _, _, m: Mining, _, _) if epochInfo.number == epochNumber && blockchain.height == epochNumber =>
        val nextBlockUnixTs = (parentBlock.timestamp + config.blockDelay.toSeconds).max(time.correctedTime() / 1000)

        startBuildingPayload(
          epochInfo,
          parentBlock,
          finalizedBlock,
          nextBlockUnixTs,
          m.lastClToElTransferIndex,
          m.lastElWithdrawalIndex,
          chainContractOptions,
          None
        ).fold[Unit](
          err => {
            logger.error(s"Error starting payload build process: ${err.message}")
            scheduler.scheduleOnce(MiningRetryInterval) {
              tryToForgeNextBlock(epochNumber, parentBlock, chainContractOptions)
            }
          },
          miningData => {
            val newState = w.copy(
              lastEcBlock = parentBlock,
              chainStatus = m.copy(
                currentPayloadId = miningData.payloadId,
                lastClToElTransferIndex = miningData.lastClToElTransferIndex,
                lastElWithdrawalIndex = miningData.lastElWithdrawalIndex
              )
            )
            setState("11", newState)
            scheduler.scheduleOnce((miningData.nextBlockUnixTs - time.correctedTime() / 1000).min(1).seconds)(
              prepareAndApplyPayload(
                miningData.payloadId,
                parentBlock.hash,
                miningData.nextBlockUnixTs,
                chainContractOptions.appendFunction,
                chainContractOptions
              )
            )
          }
        )
      case other => logger.debug(s"Unexpected state $other attempting to start building block referencing ${parentBlock.hash} at epoch $epochNumber")
    }
  }

  private def updateStartingState(): Unit = {
    val finalizedBlock = chainContractClient.getFinalizedBlock
    logger.debug(s"Finalized block is ${finalizedBlock.hash}")
    httpApiClient.getBlockByHash(finalizedBlock.hash) match {
      case Left(error) => logger.error(s"Could not load finalized block", error)
      case Right(Some(finalizedEcBlock)) =>
        logger.trace(s"Finalized block ${finalizedBlock.hash} is at height ${finalizedEcBlock.height}")
        (for {
          newEpochInfo  <- calculateEpochInfo
          mainChainInfo <- chainContractClient.getMainChainInfo.toRight("Can't get main chain info")
          lastEcBlock   <- httpApiClient.getLastExecutionBlock.leftMap(_.message)
        } yield {
          logger.trace(s"Following main chain ${mainChainInfo.id}")
          val fullValidationStatus = FullValidationStatus(
            validated = Set(finalizedBlock.hash),
            lastElWithdrawalIndex = None
          )
          val options = chainContractClient.getOptions
          followChainAndRequestNextBlock(
            newEpochInfo,
            mainChainInfo,
            lastEcBlock,
            mainChainInfo,
            finalizedBlock,
            fullValidationStatus,
            options,
            None
          )
        }).fold(
          err => logger.error(s"Could not transition to working state: $err"),
          _ => ()
        )
      case Right(None) =>
        logger.trace(s"Finalized block ${finalizedBlock.hash} is not in EC, requesting from peers")
        setState("15", WaitingForSyncHead(finalizedBlock, requestAndProcessBlock(finalizedBlock.hash)))
    }
  }

  private def handleConsensusLayerChanged(): Unit = {
    state match {
      case Starting      => updateStartingState()
      case w: Working[?] => updateWorkingState(w)
      case other         => logger.debug(s"Unprocessed state: $other")
    }
  }

  private def findAltChain(prevChainId: Long, referenceBlock: BlockHash) = {
    logger.debug(s"Trying to find alternative chain referencing $referenceBlock")

    val lastChainId          = chainContractClient.getLastChainId
    val firstValidAltChainId = chainContractClient.getFirstValidAltChainId

    val result = (firstValidAltChainId.max(prevChainId + 1) to lastChainId).foldLeft(Option.empty[ChainInfo]) {
      case (Some(chainInfo), _) => Some(chainInfo)
      case (_, chainId) =>
        val chainInfo = chainContractClient.getChainInfo(chainId)
        val isNeededAltChain = chainInfo.exists { chainInfo =>
          !chainInfo.isMain && chainInfo.firstBlock.parentHash == referenceBlock
        }
        if (isNeededAltChain) chainInfo else None
    }

    result match {
      case Some(chainInfo) =>
        logger.debug(s"Found alternative chain #${chainInfo.id} referencing $referenceBlock")
        Some(chainInfo)
      case _ =>
        logger.debug(s"Not found alternative chain referencing $referenceBlock")
        None
    }
  }

  private def requestBlocksAndStartMining(prevState: Working[FollowingChain]): Unit = {
    def check(missedBlock: BlockHash): Unit = {
      state match {
        case w @ Working(epochInfo, lastEcBlock, finalizedBlock, mainChainInfo, _, fc: FollowingChain, _, returnToMainChainInfo)
            if fc.nextExpectedEcBlock.contains(missedBlock) && canSupportAnotherAltChain(fc.nodeChainInfo) =>
          logger.debug(s"Block $missedBlock wasn't received for $WaitRequestedBlockTimeout, need to switch to alternative chain")
          val lastValidBlock = findAltChainReferenceHash(epochInfo, fc.nodeChainInfo, lastEcBlock, finalizedBlock, missedBlock)
          rollbackTo(w, lastValidBlock, finalizedBlock).fold(
            err => logger.error(err.message),
            updatedState => {
              val chainSwitchInfo = ChainSwitchInfo(fc.nodeChainInfo.id, lastValidBlock)
              val updatedReturnToMainChainInfo =
                if (fc.nodeChainInfo.isMain) {
                  Some(ReturnToMainChainInfo(missedBlock, lastEcBlock, mainChainInfo.id))
                } else returnToMainChainInfo
              val newState = updatedState.copy(chainStatus = WaitForNewChain(chainSwitchInfo), returnToMainChainInfo = updatedReturnToMainChainInfo)
              setState("9", newState)
              tryToStartMining(newState, Left(chainSwitchInfo))
            }
          )
        case w: Working[?] =>
          w.chainStatus match {
            case FollowingChain(_, Some(nextExpectedBlock)) =>
              logger.debug(s"Waiting for block $nextExpectedBlock from peers")
              scheduler.scheduleOnce(WaitRequestedBlockTimeout) {
                if (blockchain.height == prevState.epochInfo.number) {
                  check(missedBlock)
                }
              }
            case FollowingChain(nodeChainInfo, _) =>
              tryToStartMining(w, Right(nodeChainInfo))
            case WaitForNewChain(chainSwitchInfo) =>
              tryToStartMining(w, Left(chainSwitchInfo))
            case _ => logger.warn(s"Unexpected state: $w")
          }
        case other => logger.warn(s"Unexpected state: $other")
      }
    }

    prevState.chainStatus.nextExpectedEcBlock match {
      case Some(missedBlock) =>
        scheduler.scheduleOnce(WaitRequestedBlockTimeout) {
          if (blockchain.height == prevState.epochInfo.number) {
            check(missedBlock)
          }
        }
      case _ =>
        tryToStartMining(prevState, Right(prevState.chainStatus.nodeChainInfo))
    }
  }

  private def followChainAndStartMining[CS <: ChainStatus](
      prevState: Working[CS],
      newEpochInfo: EpochInfo,
      prevChainId: Long,
      finalizedEcBlock: EcBlock,
      finalizedBlock: ContractBlock,
      options: ChainContractOptions
  ): Unit = {
    updateToFollowChain(
      prevState,
      newEpochInfo,
      prevChainId,
      finalizedEcBlock,
      finalizedBlock,
      options
    ).foreach { newState =>
      requestBlocksAndStartMining(newState)
    }
  }

  private def updateMiningState(prevState: Working[Mining]): Unit = {
    chainContractClient.getMainChainInfo match {
      case Some(mainChainInfo) =>
        val newChainInfo = prevState.chainStatus.nodeChainInfo match {
          case Left(chainSwitchInfo) =>
            findAltChain(chainSwitchInfo.prevChainId, chainSwitchInfo.referenceBlock.hash)
          case Right(chainInfo) => chainContractClient.getChainInfo(chainInfo.id)
        }

        setState(
          "13",
          prevState.copy(
            mainChainInfo = mainChainInfo,
            chainStatus = prevState.chainStatus.copy(nodeChainInfo = newChainInfo.fold(prevState.chainStatus.nodeChainInfo)(Right(_))),
            returnToMainChainInfo =
              prevState.returnToMainChainInfo.filter(rInfo => !newChainInfo.map(_.id).contains(rInfo.chainId) && rInfo.chainId == mainChainInfo.id)
          )
        )
      case _ =>
        logger.error("Can't get main chain info")
        setState("14", Starting)
    }
  }

  private def updateWorkingState[CS <: ChainStatus](prevState: Working[CS]): Unit = {
    val finalizedBlock = chainContractClient.getFinalizedBlock
    val options        = chainContractClient.getOptions
    logger.debug(s"Finalized block is ${finalizedBlock.hash}")
    httpApiClient.getBlockByHash(finalizedBlock.hash) match {
      case Left(error) => logger.error(s"Could not load finalized block", error)
      case Right(Some(finalizedEcBlock)) =>
        logger.trace(s"Finalized block ${finalizedBlock.hash} is at height ${finalizedEcBlock.height}")
        if (blockchain.height != prevState.epochInfo.number || !blockchain.vrf(blockchain.height).contains(prevState.epochInfo.hitSource)) {
          calculateEpochInfo match {
            case Left(error) =>
              logger.error(s"Could not calculate epoch info at epoch start: $error")
              setState("17", Starting)
            case Right(newEpochInfo) =>
              prevState.chainStatus match {
                case FollowingChain(nodeChainInfo, _) =>
                  followChainAndStartMining(
                    prevState,
                    newEpochInfo,
                    nodeChainInfo.id,
                    finalizedEcBlock,
                    finalizedBlock,
                    options
                  )
                case m: Mining =>
                  logger.debug(s"Stop mining at epoch ${prevState.epochInfo.number}")
                  val chainId = m.nodeChainInfo.map(_.id).getOrElse(chainContractClient.getMainChainId)
                  followChainAndStartMining(
                    prevState,
                    newEpochInfo,
                    chainId,
                    finalizedEcBlock,
                    finalizedBlock,
                    options
                  )
                case WaitForNewChain(chainSwitchInfo) =>
                  findAltChain(chainSwitchInfo.prevChainId, chainSwitchInfo.referenceBlock.hash) match {
                    case Some(chainInfo) =>
                      followChainAndStartMining(
                        prevState,
                        newEpochInfo,
                        chainInfo.id,
                        finalizedEcBlock,
                        finalizedBlock,
                        options
                      )
                    case _ =>
                      val newState = prevState.copy(epochInfo = newEpochInfo)
                      setState("18", newState)
                      tryToStartMining(newState, Left(chainSwitchInfo))
                  }
              }
          }
        } else {
          prevState.chainStatus match {
            case FollowingChain(nodeChainInfo, _) =>
              updateToFollowChain(
                prevState,
                prevState.epochInfo,
                nodeChainInfo.id,
                finalizedEcBlock,
                finalizedBlock,
                options
              )
            case m: Mining => updateMiningState(prevState.copy(chainStatus = m))
            case WaitForNewChain(chainSwitchInfo) =>
              val newChainInfo = findAltChain(chainSwitchInfo.prevChainId, chainSwitchInfo.referenceBlock.hash)
              newChainInfo.foreach { chainInfo =>
                updateToFollowChain(prevState, prevState.epochInfo, chainInfo.id, finalizedEcBlock, finalizedBlock, options)
              }
          }
        }
        fullValidation()
        requestMainChainBlock(finalizedBlock)
      case Right(None) =>
        logger.trace(s"Finalized block ${finalizedBlock.hash} is not in EC, requesting from peers")
        setState("19", WaitingForSyncHead(finalizedBlock, requestAndProcessBlock(finalizedBlock.hash)))
    }
  }

  private def followChainAndRequestNextBlock(
      epochInfo: EpochInfo,
      nodeChainInfo: ChainInfo,
      lastEcBlock: EcBlock,
      mainChainInfo: ChainInfo,
      finalizedBlock: ContractBlock,
      fullValidationStatus: FullValidationStatus,
      options: ChainContractOptions,
      returnToMainChainInfo: Option[ReturnToMainChainInfo]
  ): Working[FollowingChain] = {
    val newState = Working(
      epochInfo,
      lastEcBlock,
      finalizedBlock,
      mainChainInfo,
      fullValidationStatus,
      FollowingChain(nodeChainInfo, None),
      options,
      returnToMainChainInfo
    )
    setState("3", newState)
    maybeRequestNextBlock(newState, finalizedBlock)
  }

  private def requestBlock(hash: BlockHash): BlockRequestResult = {
    logger.debug(s"Requesting block $hash")
    httpApiClient.getBlockByHash(hash) match {
      case Right(Some(block)) => BlockRequestResult.BlockExists(block)
      case Right(None) =>
        requestAndProcessBlock(hash)
        BlockRequestResult.Requested(hash)
      case Left(err) =>
        logger.warn(s"Failed to get block $hash by hash: ${err.message}")
        requestAndProcessBlock(hash)
        BlockRequestResult.Requested(hash)
    }
  }

  private def requestMainChainBlock(finalizedBlock: ContractBlock): Unit = {
    state match {
      case w: Working[?] =>
        w.returnToMainChainInfo.foreach { returnToMainChainInfo =>
          if (w.mainChainInfo.id == returnToMainChainInfo.chainId) {
            requestBlock(returnToMainChainInfo.missedEcBlockHash) match {
              case BlockRequestResult.BlockExists(block) =>
                (for {
                  _ <- confirmBlock(block, finalizedBlock)
                  mainChainInfo <- chainContractClient
                    .getChainInfo(returnToMainChainInfo.chainId)
                    .toRight(s"Failed to get chain ${returnToMainChainInfo.chainId} info: not found")
                } yield mainChainInfo) match {
                  case Right(mainChainInfo) =>
                    followChainAndRequestNextBlock(
                      w.epochInfo,
                      mainChainInfo,
                      block,
                      mainChainInfo,
                      finalizedBlock,
                      w.fullValidationStatus,
                      w.options,
                      None
                    )
                    logger.info(s"Successfully confirmed block ${block.hash} of chain ${returnToMainChainInfo.chainId}")
                    fullValidation()
                  case Left(err) =>
                    logger.error(s"Failed to confirm block ${block.hash} of chain ${returnToMainChainInfo.chainId}: $err")
                }
              case BlockRequestResult.Requested(_) =>
            }
          }
        }
      case _ =>
    }
  }

  private def requestAndProcessBlock(hash: BlockHash): CancelableFuture[(Channel, NetworkL2Block)] = {
    requestBlockFromPeers(hash).andThen {
      case Success((ch, block)) => executionBlockReceived(block, ch)
      case Failure(exception)   => logger.error(s"Error loading block $hash", exception)
    }(globalScheduler)
  }

  private def updateToFollowChain[C <: ChainStatus](
      prevState: Working[C],
      epochInfo: EpochInfo,
      prevChainId: Long,
      finalizedEcBlock: EcBlock,
      finalizedContractBlock: ContractBlock,
      options: ChainContractOptions
  ): Option[Working[FollowingChain]] = {
    @tailrec
    def findLastEcBlock(curBlock: ContractBlock): EcBlock = {
      httpApiClient.getBlockByHash(curBlock.hash) match {
        case Right(Some(block)) => block
        case Right(_) =>
          chainContractClient.getBlock(curBlock.parentHash) match {
            case Some(parent) => findLastEcBlock(parent)
            case _ =>
              logger.warn(s"Block ${curBlock.parentHash} not found at contract")
              finalizedEcBlock
          }
        case Left(err) =>
          logger.warn(s"Failed to get block ${curBlock.hash} by hash: ${err.message}")
          finalizedEcBlock
      }
    }

    def followChain[CS <: ChainStatus](
        nodeChainInfo: ChainInfo,
        lastEcBlock: EcBlock,
        mainChainInfo: ChainInfo,
        fullValidationStatus: FullValidationStatus,
        returnToMainChainInfo: Option[ReturnToMainChainInfo]
    ): Working[FollowingChain] = {
      val newState = Working(
        epochInfo,
        lastEcBlock,
        finalizedContractBlock,
        mainChainInfo,
        fullValidationStatus,
        FollowingChain(nodeChainInfo, None),
        options,
        returnToMainChainInfo.filter(rInfo => rInfo.chainId != prevChainId && mainChainInfo.id == rInfo.chainId)
      )
      setState("16", newState)
      maybeRequestNextBlock(newState, finalizedContractBlock)
    }

    def rollbackAndFollowChain(
        target: L2BlockLike,
        nodeChainInfo: ChainInfo,
        mainChainInfo: ChainInfo,
        returnToMainChainInfo: Option[ReturnToMainChainInfo]
    ): Option[Working[FollowingChain]] = {
      rollbackTo(prevState, target, finalizedContractBlock) match {
        case Right(updatedState) =>
          Some(followChain(nodeChainInfo, updatedState.lastEcBlock, mainChainInfo, updatedState.fullValidationStatus, returnToMainChainInfo))
        case Left(err) =>
          logger.error(s"Failed to rollback to ${target.hash}: ${err.message}")
          None
      }
    }

    def rollbackAndFollowMainChain(target: L2BlockLike, mainChainInfo: ChainInfo): Option[Working[FollowingChain]] =
      rollbackAndFollowChain(target, mainChainInfo, mainChainInfo, None)

    (chainContractClient.getMainChainInfo, chainContractClient.getChainInfo(prevChainId)) match {
      case (Some(mainChainInfo), Some(prevChainInfo)) =>
        if (mainChainInfo.id != prevState.mainChainInfo.id) {
          val updatedLastEcBlock = findLastEcBlock(mainChainInfo.lastBlock)
          rollbackAndFollowMainChain(updatedLastEcBlock, mainChainInfo)
        } else if (prevChainInfo.firstBlock.height < finalizedContractBlock.height && !prevChainInfo.isMain) {
          val targetBlockHash = prevChainInfo.firstBlock.parentHash
          chainContractClient.getBlock(targetBlockHash) match {
            case Some(targetBlock) => rollbackAndFollowMainChain(targetBlock, mainChainInfo)
            case None =>
              logger.error(s"Failed to get block $targetBlockHash meta at contract")
              None
          }
        } else if (isLastEcBlockOnFork(prevChainInfo, prevState.lastEcBlock)) {
          val updatedLastEcBlock = findLastEcBlock(prevChainInfo.lastBlock)
          rollbackAndFollowChain(updatedLastEcBlock, prevChainInfo, mainChainInfo, prevState.returnToMainChainInfo)
        } else {
          Some(followChain(prevChainInfo, prevState.lastEcBlock, mainChainInfo, prevState.fullValidationStatus, prevState.returnToMainChainInfo))
        }
      case (Some(mainChainInfo), None) =>
        rollbackAndFollowMainChain(finalizedEcBlock, mainChainInfo)
      case (None, _) =>
        logger.error("Failed to get main chain info")
        None
    }
  }

  private def isLastEcBlockOnFork(chainInfo: ChainInfo, lastEcBlock: EcBlock) =
    chainInfo.lastBlock.height == lastEcBlock.height && chainInfo.lastBlock.hash != lastEcBlock.hash ||
      chainInfo.lastBlock.height > lastEcBlock.height && !chainContractClient.blockExists(lastEcBlock.hash) ||
      chainInfo.lastBlock.height < lastEcBlock.height

  private def waitForSyncCompletion(target: ContractBlock): Unit = scheduler.scheduleOnce(5.seconds)(state match {
    case SyncingToFinalizedBlock(finalizedBlockHash) if finalizedBlockHash == target.hash =>
      logger.debug(s"Checking if EL has synced to ${target.hash} on height ${target.height}")
      httpApiClient.getLastExecutionBlock match {
        case Left(error) =>
          logger.error(s"Sync to ${target.hash} was not completed, error=${error.message}")
          setState("23", Starting)
        case Right(lastBlock) if lastBlock.hash == target.hash =>
          logger.debug(s"Finished synchronization to ${target.hash} successfully")
          calculateEpochInfo match {
            case Left(err) =>
              logger.error(s"Could not transition to following chain state: $err")
              setState("24", Starting)
            case Right(newEpochInfo) =>
              chainContractClient.getMainChainInfo match {
                case Some(mainChainInfo) =>
                  logger.trace(s"Following main chain ${mainChainInfo.id}")
                  val fullValidationStatus = FullValidationStatus(validated = Set(finalizedBlockHash), lastElWithdrawalIndex = None)
                  followChainAndRequestNextBlock(
                    newEpochInfo,
                    mainChainInfo,
                    lastBlock,
                    mainChainInfo,
                    target,
                    fullValidationStatus,
                    chainContractClient.getOptions,
                    None
                  )
                case _ =>
                  logger.error(s"Can't get main chain info")
                  setState("25", Starting)
              }
          }
        case Right(lastBlock) =>
          logger.debug(s"Sync to ${target.hash} is in progress: current last block is ${lastBlock.hash} at height ${lastBlock.height}")
          waitForSyncCompletion(target)
      }
    case other =>
      logger.debug(s"Unexpected state: $other")
  })

  private def checkSignature(block: NetworkL2Block, isConfirmed: Boolean): Either[String, Unit] = {
    if (isConfirmed) {
      Right(())
    } else {
      for {
        signature <- Either.fromOption(block.signature, s"signature not found")
        publicKey <- Either.fromOption(
          chainContractClient.getMinerPublicKey(block.minerRewardL2Address),
          s"public key for block miner ${block.minerRewardL2Address} not found"
        )
        _ <- Either.cond(
          crypto.verify(signature, Json.toBytes(block.payload), publicKey, checkWeakPk = true),
          (),
          s"invalid signature"
        )
      } yield ()
    }
  }

  private def preValidateWithdrawals(
      networkBlock: NetworkL2Block,
      epochInfo: EpochInfo,
      contractBlock: Option[ContractBlock]
  ): Either[String, PreValidationResult] = {

    lazy val isEpochFirstOnContract = contractBlock.fold(false) { block =>
      val parentBlock = chainContractClient
        .getBlock(networkBlock.parentHash)
        .getOrElse(
          throw new RuntimeException(s"Can't find parent block ${networkBlock.parentHash} of ${networkBlock.hash} on chain contract")
        )
      parentBlock.epoch < block.epoch
    }

    lazy val isEpochFirstBlock = isEpochFirstOnContract || epochInfo.prevEpochLastBlockHash.contains(networkBlock.parentHash)
    val expectReward           = !networkBlock.referencesGenesis && isEpochFirstBlock

    if (expectReward)
      Either.cond(
        networkBlock.withdrawals.nonEmpty,
        PreValidationResult(expectReward = true),
        s"expected at least one withdrawal, but got none. " +
          s"References genesis? ${networkBlock.referencesGenesis}. Is new epoch? $isEpochFirstBlock"
      )
    else // We don't assert that there is no withdrawals, because of possible users' withdrawals
      PreValidationResult(expectReward = false).asRight
  }

  private def preValidateBlock(
      networkBlock: NetworkL2Block,
      expectedParent: EcBlock,
      epochInfo: EpochInfo,
      contractBlock: Option[ContractBlock]
  ): Either[String, PreValidationResult] = {
    val now         = time.correctedTime() / 1000
    val isConfirmed = contractBlock.nonEmpty
    (for {
      _ <- Either.cond(
        networkBlock.parentHash == expectedParent.hash,
        (),
        s"block doesn't reference expected execution block ${expectedParent.hash}"
      )
      _ <- Either.cond(
        networkBlock.timestamp - now <= MaxTimeDrift,
        (),
        s"block from future: new block timestamp=${networkBlock.timestamp}, now=$now, Δ${networkBlock.timestamp - now}s"
      )
      _ <- Either.cond(
        isConfirmed || networkBlock.minerRewardL2Address == epochInfo.rewardAddress,
        (),
        s"block miner ${networkBlock.minerRewardL2Address} doesn't equal to ${epochInfo.rewardAddress}"
      )
      _      <- checkSignature(networkBlock, isConfirmed)
      _      <- if (contractBlock.isEmpty) validateRandao(networkBlock, epochInfo.number) else Either.unit
      result <- preValidateWithdrawals(networkBlock, epochInfo, contractBlock)
    } yield result).leftMap { err =>
      s"Block ${networkBlock.hash} validation error: $err, ignoring block"
    }
  }

  private def validateBlock(
      networkBlock: NetworkL2Block,
      parentEcBlock: EcBlock,
      contractBlock: Option[ContractBlock],
      expectReward: Boolean,
      chainContractOptions: ChainContractOptions
  ): Job[Unit] = {
    (for {
      _ <- validateTimestamp(networkBlock, parentEcBlock)
      _ <- validateWithdrawals(networkBlock, parentEcBlock, expectReward, chainContractOptions)
      _ <- contractBlock.fold(Either.unit[String])(cb => validateRandao(networkBlock, cb.epoch))
    } yield ()).leftMap(err => ClientError(s"Network block ${networkBlock.hash} validation error: $err"))
  }

  private def validateTimestamp(newNetworkBlock: NetworkL2Block, parentEcBlock: EcBlock): Either[String, Unit] = {
    val minAppendTs = parentEcBlock.timestamp + config.blockDelay.toSeconds
    Either.cond(
      newNetworkBlock.timestamp >= minAppendTs,
      (),
      s"Timestamp (${newNetworkBlock.timestamp}) of appended block must be greater or equal $minAppendTs, " +
        s"Δ${minAppendTs - newNetworkBlock.timestamp}s"
    )
  }

  private def validateWithdrawals(
      networkBlock: NetworkL2Block,
      parentEcBlock: EcBlock,
      expectReward: Boolean,
      chainContractOptions: ChainContractOptions
  ): Either[String, Unit] = {
    if (expectReward) {
      for {
        minerReward <- networkBlock.withdrawals.headOption.toRight(s"Expected at least one withdrawal (reward), got none")
        _ <- Either.cond(
          minerReward.address == parentEcBlock.minerRewardL2Address,
          (),
          s"Unexpected reward address: ${minerReward.address}, expected: ${parentEcBlock.minerRewardL2Address}"
        )
        _ <- Either.cond(
          minerReward.amount == chainContractOptions.miningReward,
          (),
          s"Unexpected reward amount: ${minerReward.amount}, expected: ${chainContractOptions.miningReward}"
        )
      } yield ()
    } else Either.unit[String]
  }

  private def findAltChainReferenceHash(
      epochInfo: EpochInfo,
      nodeChainInfo: ChainInfo,
      lastEcBlock: EcBlock,
      finalizedBlock: ContractBlock,
      invalidBlockHash: BlockHash
  ): ContractBlock = {
    @tailrec
    def loop(curBlock: ContractBlock, invalidBlockEpoch: Int): ContractBlock = {
      if (curBlock.height == finalizedBlock.height) {
        curBlock
      } else {
        chainContractClient.getBlock(curBlock.hash) match {
          case Some(block) if block.epoch < invalidBlockEpoch => curBlock
          case Some(_) =>
            loop(chainContractClient.getBlock(curBlock.parentHash).getOrElse(finalizedBlock), invalidBlockEpoch)
          case _ => finalizedBlock
        }
      }
    }

    if (nodeChainInfo.isMain) {
      val startBlock        = chainContractClient.getBlock(lastEcBlock.hash).getOrElse(nodeChainInfo.lastBlock)
      val invalidBlockEpoch = chainContractClient.getBlock(invalidBlockHash).map(_.epoch).getOrElse(epochInfo.number)

      loop(startBlock, invalidBlockEpoch)
    } else {
      val blockId = nodeChainInfo.firstBlock.parentHash
      chainContractClient.getBlock(blockId) match {
        case Some(block) => block
        case None =>
          logger.warn(s"Parent block $blockId for first block ${nodeChainInfo.firstBlock.hash} of chain ${nodeChainInfo.id} not found at contract")
          finalizedBlock
      }
    }
  }

  private def validateAndApply[CS <: ChainStatus](
      networkBlock: NetworkL2Block,
      ch: Channel,
      prevState: Working[CS],
      expectedParent: EcBlock,
      nodeChainInfo: ChainInfo,
      returnToMainChainInfo: Option[ReturnToMainChainInfo],
      ignoreInvalid: Boolean
  ): Unit = {
    val contractBlock = chainContractClient.getBlock(networkBlock.hash)
    preValidateBlock(networkBlock, expectedParent, prevState.epochInfo, contractBlock) match {
      case Left(err) =>
        logger.debug(err)
      case Right(preValidationResult) =>
        val applyResult = for {
          _ <- validateBlock(networkBlock, expectedParent, contractBlock, preValidationResult.expectReward, prevState.options)
          _ = logger.debug(s"Block ${networkBlock.hash} was successfully validated, trying to apply and broadcast")
          _ <- engineApiClient.applyNewPayload(networkBlock.payload)
        } yield ()
        applyResult match {
          case Left(err) =>
            if (ignoreInvalid) {
              logger.error(s"Ignoring invalid block ${networkBlock.hash}: ${err.message}")
            } else {
              logger.error(err.message)

              val lastValidBlock =
                findAltChainReferenceHash(prevState.epochInfo, nodeChainInfo, expectedParent, prevState.finalizedBlock, networkBlock.hash)
              rollbackTo(prevState, lastValidBlock, prevState.finalizedBlock).fold(
                err => logger.error(err.message),
                updatedState =>
                  findAltChain(nodeChainInfo.id, lastValidBlock.hash) match {
                    case Some(altChainInfo) =>
                      setState(
                        "20",
                        updatedState.copy(
                          chainStatus = FollowingChain(altChainInfo, None),
                          returnToMainChainInfo = if (nodeChainInfo.isMain) None else updatedState.returnToMainChainInfo
                        )
                      )
                    case _ =>
                      setState(
                        "21",
                        updatedState.copy(
                          chainStatus = WaitForNewChain(ChainSwitchInfo(nodeChainInfo.id, lastValidBlock)),
                          returnToMainChainInfo = if (nodeChainInfo.isMain) None else updatedState.returnToMainChainInfo
                        )
                      )
                  }
              )
            }
          case _ =>
            Try(allChannels.broadcast(networkBlock, Some(ch))).recover { err =>
              logger.error(s"Failed to broadcast block ${networkBlock.hash}: ${err.getMessage}")
            }

            val finalizedBlock = prevState.finalizedBlock
            confirmBlock(networkBlock, finalizedBlock)
              .fold[Unit](
                err => logger.error(s"Can't confirm block ${networkBlock.hash} of chain ${nodeChainInfo.id}: ${err.message}"),
                _ => {
                  logger.info(s"Successfully applied block ${networkBlock.hash} to chain ${nodeChainInfo.id}")
                  followChainAndRequestNextBlock(
                    prevState.epochInfo,
                    nodeChainInfo,
                    networkBlock.toEcBlock,
                    prevState.mainChainInfo,
                    finalizedBlock,
                    prevState.fullValidationStatus,
                    prevState.options,
                    returnToMainChainInfo
                  )
                  fullValidation()
                }
              )
        }
    }
  }

  private def findBlockChild(parent: BlockHash, lastBlockHash: BlockHash): Either[String, BlockHash] = {
    @tailrec
    def loop(b: BlockHash): Option[BlockHash] = chainContractClient.getBlock(b) match {
      case None => None
      case Some(cb) =>
        if (cb.parentHash == parent) Some(cb.hash)
        else loop(cb.parentHash)
    }

    loop(lastBlockHash).toRight(s"Could not find child of $parent")
  }

  @tailrec
  private def maybeRequestNextBlock(prevState: Working[FollowingChain], finalizedBlock: ContractBlock): Working[FollowingChain] = {
    if (prevState.lastEcBlock.height < prevState.chainStatus.nodeChainInfo.lastBlock.height) {
      logger.debug(s"EC chain is not synced, trying to find next block to request")
      findBlockChild(prevState.lastEcBlock.hash, prevState.chainStatus.nodeChainInfo.lastBlock.hash) match {
        case Left(error) =>
          logger.error(s"Could not find child of ${prevState.lastEcBlock.hash} on contract: $error")
          prevState
        case Right(hash) =>
          requestBlock(hash) match {
            case BlockRequestResult.BlockExists(block) =>
              logger.debug(s"Block $hash exists at EC chain, trying to confirm")
              confirmBlock(block, finalizedBlock) match {
                case Right(_) =>
                  val newState = prevState.copy(
                    lastEcBlock = block,
                    chainStatus = FollowingChain(prevState.chainStatus.nodeChainInfo, None)
                  )
                  setState("7", newState)
                  maybeRequestNextBlock(newState, finalizedBlock)
                case Left(err) =>
                  logger.error(s"Failed to confirm next block ${block.hash}: ${err.message}")
                  prevState
              }
            case BlockRequestResult.Requested(hash) =>
              val newState = prevState.copy(chainStatus = prevState.chainStatus.copy(nextExpectedEcBlock = Some(hash)))
              setState("8", newState)
              newState
          }
      }
    } else {
      logger.trace(s"EC chain ${prevState.chainStatus.nodeChainInfo.id} is synced, no need to request blocks")
      prevState
    }
  }

  private def mkRollbackBlock(rollbackTargetBlockId: BlockHash): Job[RollbackBlock] = for {
    targetBlockFromContract <- Right(chainContractClient.getBlock(rollbackTargetBlockId))
    targetBlockOpt <- targetBlockFromContract match {
      case None => httpApiClient.getBlockByHash(rollbackTargetBlockId)
      case x    => Right(x)
    }
    targetBlock      <- Either.fromOption(targetBlockOpt, ClientError(s"Can't find block $rollbackTargetBlockId neither on a contract, nor in EC"))
    parentBlock      <- httpApiClient.getBlockByHash(targetBlock.parentHash)
    parentBlock      <- Either.fromOption(parentBlock, ClientError(s"Can't find parent block $rollbackTargetBlockId in execution client"))
    rollbackBlockOpt <- engineApiClient.applyNewPayload(EmptyL2Block.mkExecutionPayload(parentBlock))
    rollbackBlock    <- Either.fromOption(rollbackBlockOpt, ClientError("Rollback block hash is not defined as latest valid hash"))
  } yield RollbackBlock(rollbackBlock, parentBlock)

  private def toWithdrawals(transfers: Vector[ChainContractClient.ContractTransfer], firstWithdrawalIndex: Long): Vector[Withdrawal] =
    transfers.zipWithIndex.map { case (x, i) =>
      val index = firstWithdrawalIndex + i
      Withdrawal(index, x.destElAddress, Bridge.clToGweiNativeTokenAmount(x.amount))
    }

  private def getLastWithdrawalIndex(hash: BlockHash): Job[WithdrawalIndex] =
    httpApiClient.getBlockByHash(hash).flatMap {
      case None => Left(ClientError(s"Can't find $hash block on EC during withdrawal search"))
      case Some(ecBlock) =>
        ecBlock.withdrawals.lastOption match {
          case Some(lastWithdrawal) => Right(lastWithdrawal.index)
          case None =>
            if (ecBlock.height == 0) Right(-1L)
            else getLastWithdrawalIndex(ecBlock.parentHash)
        }
    }

  private def getElToClTransfersRootHash(hash: BlockHash, elBridgeAddress: EthAddress): Job[Digest] =
    for {
      elRawLogs <- httpApiClient.getLogs(hash, Bridge.ElSentNativeEventTopic)
      rootHash <- {
        val relatedElRawLogs = elRawLogs.filter(x => x.address == elBridgeAddress && x.topics.contains(Bridge.ElSentNativeEventTopic))
        Bridge
          .mkTransfersHash(relatedElRawLogs)
          .leftMap(e => ClientError(e))
          .map { rootHash =>
            if (rootHash.isEmpty) rootHash
            else {
              logger.debug(s"EL->CL transfers root hash of $hash: ${EthEncoding.toHexString(rootHash)}")
              rootHash
            }
          }
      }
    } yield rootHash

  private def fullValidation(): Unit = {
    (state match {
      case w: Working[?] =>
        getBlockForValidation(w.lastContractBlock, w.lastEcBlock, w.finalizedBlock, w.fullValidationStatus).flatMap {
          case BlockForValidation.Found(contractBlock, ecBlock) => fullBlockValidation(contractBlock, ecBlock, w)
          case BlockForValidation.SkippedFinalized(block) =>
            logger.debug(s"Full validation of ${block.hash} skipped, because of finalization")
            setState("4", w.copy(fullValidationStatus = w.fullValidationStatus.copy(validated = Set(block.hash), lastElWithdrawalIndex = None)))
            Either.unit
          case BlockForValidation.NotFound => Either.unit
        }
      case other =>
        logger.debug(s"Skipping full validation: $other")
        Either.unit
    }).fold(
      err => logger.warn(s"Full validation error: ${err.message}"),
      identity
    )
  }

  private def validateElToClTransfers(contractBlock: ContractBlock, elBridgeAddress: EthAddress): Job[Unit] =
    getElToClTransfersRootHash(contractBlock.hash, elBridgeAddress).flatMap { elRootHash =>
      // elRootHash is the source of true
      if (java.util.Arrays.equals(contractBlock.elToClTransfersRootHash, elRootHash)) Either.unit
      else
        Left(
          ClientError(
            s"EL to CL transfers hash of ${contractBlock.hash} are different: " +
              s"EL=${toHexNoPrefix(elRootHash)}, " +
              s"CL=${toHexNoPrefix(contractBlock.elToClTransfersRootHash)}"
          )
        )
    }

  private def fullWithdrawalsValidation(
      contractBlock: ContractBlock,
      ecBlock: EcBlock,
      fullValidationStatus: FullValidationStatus,
      chainContractOptions: ChainContractOptions
  ): Job[Option[WithdrawalIndex]] = {
    val blockEpoch = chainContractClient
      .getEpochMeta(contractBlock.epoch)
      .getOrElse(throw new RuntimeException(s"Can't find an epoch ${contractBlock.epoch} data of block ${contractBlock.hash} on chain contract"))

    val blockPrevEpoch = chainContractClient
      .getEpochMeta(blockEpoch.prevEpoch)
      .getOrElse(
        throw new RuntimeException(s"Can't find a prev epoch ${blockEpoch.prevEpoch} data of block ${contractBlock.hash} on chain contract")
      )

    val isEpochFirstBlock        = contractBlock.parentHash == blockPrevEpoch.lastBlockHash
    val expectMiningReward       = isEpochFirstBlock && !contractBlock.referencesGenesis
    val prevMinerElRewardAddress = if (expectMiningReward) chainContractClient.getL2RewardAddress(blockPrevEpoch.miner) else None

    for {
      elWithdrawalIndexBefore <- fullValidationStatus.checkedLastElWithdrawalIndex(ecBlock.parentHash) match {
        case Some(r) => Right(r)
        case None =>
          if (ecBlock.height - 1 <= EthereumConstants.GenesisBlockHeight) Right(-1L)
          else getLastWithdrawalIndex(ecBlock.parentHash)
      }
      lastElWithdrawalIndex <- validateClToElTransfers(
        ecBlock,
        contractBlock,
        prevMinerElRewardAddress,
        chainContractOptions,
        elWithdrawalIndexBefore
      )
        .leftMap(ClientError.apply)
    } yield Some(lastElWithdrawalIndex)
  }

  // Note: we can not do this validation before block application, because we need block logs
  private def fullBlockValidation[CS <: ChainStatus](
      contractBlock: ContractBlock,
      ecBlock: EcBlock,
      prevState: Working[CS]
  ): Job[Unit] = {
    logger.debug(s"Full validation of ${contractBlock.hash}")
    val validation = for {
      _                            <- validateElToClTransfers(contractBlock, prevState.options.elBridgeAddress)
      updatedLastElWithdrawalIndex <- fullWithdrawalsValidation(contractBlock, ecBlock, prevState.fullValidationStatus, prevState.options)
    } yield updatedLastElWithdrawalIndex

    validation
      .map { lastElWithdrawalIndex =>
        setState(
          "5",
          prevState.copy(fullValidationStatus =
            FullValidationStatus(
              validated = Set(contractBlock.hash),
              lastElWithdrawalIndex = lastElWithdrawalIndex
            )
          )
        )
      }
      .recoverWith { e =>
        logger.debug(s"Full validation of ${contractBlock.hash} failed: ${e.message}")
        chainContractClient.getChainInfo(contractBlock.chainId) match {
          case Some(nodeChainInfo) if canSupportAnotherAltChain(nodeChainInfo) =>
            for {
              lastEpoch <- chainContractClient
                .getEpochMeta(contractBlock.epoch)
                .toRight(
                  ClientError(
                    s"Impossible case: can't find the epoch #${contractBlock.epoch} metadata of invalid block ${contractBlock.hash} on contract"
                  )
                )
              prevEpoch <- chainContractClient
                .getEpochMeta(lastEpoch.prevEpoch)
                .toRight(ClientError(s"Impossible case: can't find a previous epoch #${lastEpoch.prevEpoch} metadata on contract"))
              toBlockHash = prevEpoch.lastBlockHash
              toBlock <- chainContractClient
                .getBlock(toBlockHash)
                .toRight(ClientError(s"Impossible case: can't find a last block $toBlockHash of epoch #${lastEpoch.prevEpoch} on contract"))
              updatedState <- rollbackTo(prevState, toBlock, prevState.finalizedBlock)
              lastValidBlock <- chainContractClient
                .getBlock(updatedState.lastEcBlock.hash)
                .toRight(ClientError(s"Block ${updatedState.lastEcBlock.hash} not found at contract"))
            } yield {
              setState(
                "6",
                updatedState.copy(
                  fullValidationStatus = FullValidationStatus(
                    validated = Set(toBlockHash, contractBlock.hash),
                    lastElWithdrawalIndex = None
                  ),
                  chainStatus = WaitForNewChain(ChainSwitchInfo(contractBlock.chainId, lastValidBlock)),
                  returnToMainChainInfo = None
                )
              )
            }
          case Some(_) =>
            logger.debug(s"Ignoring invalid block ${contractBlock.hash}")
            Either.unit
          case _ =>
            logger.warn(s"Chain ${contractBlock.chainId} meta not found at contract")
            Either.unit
        }
      }
  }

  private def getBlockForValidation(
      lastContractBlock: ContractBlock,
      lastEcBlock: EcBlock,
      finalizedBlock: ContractBlock,
      fullValidationStatus: FullValidationStatus
  ): Job[BlockForValidation] = {
    if (lastContractBlock.height <= lastEcBlock.height) {
      // Checking height on else to avoid unnecessary logs
      if (fullValidationStatus.validated.contains(lastContractBlock.hash)) Right(BlockForValidation.NotFound)
      else if (lastContractBlock.height <= finalizedBlock.height) Right(BlockForValidation.SkippedFinalized(lastContractBlock))
      else
        httpApiClient
          .getBlockByHash(lastContractBlock.hash)
          .map {
            case Some(ecBlock) => BlockForValidation.Found(lastContractBlock, ecBlock)
            case None =>
              logger.debug(s"Can't find a block ${lastContractBlock.hash} on EC client for a full validation")
              BlockForValidation.NotFound
          }
    } else
      Right {
        if (fullValidationStatus.validated.contains(lastEcBlock.hash)) BlockForValidation.NotFound
        else if (lastEcBlock.height <= finalizedBlock.height) BlockForValidation.SkippedFinalized(lastEcBlock)
        else
          chainContractClient.getBlock(lastEcBlock.hash) match {
            case None                => BlockForValidation.NotFound
            case Some(contractBlock) => BlockForValidation.Found(contractBlock, lastEcBlock)
          }
      }
  }

  private def validateClToElTransfers(
      ecBlock: EcBlock,
      contractBlock: ContractBlock,
      prevMinerElRewardAddress: Option[EthAddress],
      options: ChainContractOptions,
      elWithdrawalIndexBefore: WithdrawalIndex
  ): Either[String, WithdrawalIndex] = {
    val parentContractBlock = chainContractClient
      .getBlock(contractBlock.parentHash)
      .getOrElse(throw new RuntimeException(s"Can't find a parent block ${contractBlock.parentHash} of block ${contractBlock.hash}"))

    val expectedTransfers = chainContractClient.getNativeTransfers(
      parentContractBlock.lastClToElTransferIndex + 1,
      contractBlock.lastClToElTransferIndex - parentContractBlock.lastClToElTransferIndex
    )

    val firstWithdrawalIndex = elWithdrawalIndexBefore + 1
    for {
      expectedWithdrawals <- prevMinerElRewardAddress match {
        case None =>
          if (ecBlock.withdrawals.size == expectedTransfers.size) toWithdrawals(expectedTransfers, firstWithdrawalIndex).asRight
          else s"Expected ${expectedTransfers.size} withdrawals, got ${ecBlock.withdrawals.size}".asLeft

        case Some(prevMinerElRewardAddress) =>
          if (ecBlock.withdrawals.size == expectedTransfers.size + 1) { // +1 for reward
            val rewardWithdrawal = Withdrawal(firstWithdrawalIndex, prevMinerElRewardAddress, options.miningReward)
            val userWithdrawals  = toWithdrawals(expectedTransfers, rewardWithdrawal.index + 1)

            (rewardWithdrawal +: userWithdrawals).asRight
          } else s"Expected ${expectedTransfers.size + 1} (at least reward) withdrawals, got ${ecBlock.withdrawals.size}".asLeft
      }
      _ <- validateClToElTransfers(ecBlock, expectedWithdrawals)
    } yield expectedWithdrawals.lastOption.fold(elWithdrawalIndexBefore)(_.index)
  }

  private def validateClToElTransfers(ecBlock: EcBlock, expectedWithdrawals: Seq[Withdrawal]): Either[String, Unit] =
    ecBlock.withdrawals
      .zip(expectedWithdrawals)
      .zipWithIndex
      .toList
      .traverse { case ((actual, expected), i) =>
        for {
          _ <- Either.cond(
            actual.index == expected.index,
            (),
            s"Withdrawal #$i: expected index ${expected.index}, got ${actual.index} for $actual"
          )
          _ <- Either.cond(
            actual.address == expected.address,
            (),
            s"Withdrawal #$i: expected address ${expected.address}, got: ${actual.address}"
          )
          _ <- Either.cond(
            actual.amount == expected.amount,
            (),
            s"Withdrawal #$i: expected amount ${expected.amount}, got: ${actual.amount}"
          )
        } yield ()
      }
      .map(_ => ())

  private def confirmBlock(block: L2BlockLike, finalizedBlock: L2BlockLike): Job[String] = {
    val finalizedBlockHash = if (finalizedBlock.height > block.height) block.hash else finalizedBlock.hash
    engineApiClient.forkChoiceUpdate(block.hash, finalizedBlockHash)
  }

  private def confirmBlock(hash: BlockHash, finalizedBlockHash: BlockHash): Job[String] =
    engineApiClient.forkChoiceUpdate(hash, finalizedBlockHash)

  private def confirmBlockAndStartMining(
      lastBlock: EcBlock,
      finalizedBlock: ContractBlock,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal]
  ): Job[String] = {
    val finalizedBlockHash = if (finalizedBlock.height > lastBlock.height) lastBlock.hash else finalizedBlock.hash
    engineApiClient
      .forkChoiceUpdateWithPayloadId(
        lastBlock.hash,
        finalizedBlockHash,
        unixEpochSeconds,
        suggestedFeeRecipient,
        prevRandao,
        withdrawals
      )
  }

  private def canSupportAnotherAltChain(nodeChainInfo: ChainInfo): Boolean = {
    val chainSupporters = chainContractClient.getSupporters(nodeChainInfo.id)
    val walletAddresses = wallet.privateKeyAccounts.map(_.toAddress).toSet

    nodeChainInfo.isMain || walletAddresses.intersect(chainSupporters).isEmpty
  }

  private def setState(label: String, newState: State): Unit = {
    logger.trace(s"New state after $label: $newState")
    state = newState
  }
}

object ELUpdater {
  private val MaxTimeDrift: Int                               = 1 // second
  private val WaitForReferenceConfirmInterval: FiniteDuration = 500.millis
  val ClChangedProcessingDelay: FiniteDuration                = 50.millis
  private val MiningRetryInterval: FiniteDuration             = 5.seconds
  private val WaitRequestedBlockTimeout: FiniteDuration       = 2.seconds

  case class EpochInfo(number: Int, miner: Address, rewardAddress: EthAddress, hitSource: ByteStr, prevEpochLastBlockHash: Option[BlockHash])

  sealed trait State
  object State {
    case object Starting extends State

    case class Working[CS <: ChainStatus](
        epochInfo: EpochInfo,
        lastEcBlock: EcBlock,
        finalizedBlock: ContractBlock,
        mainChainInfo: ChainInfo,
        fullValidationStatus: FullValidationStatus,
        chainStatus: CS,
        options: ChainContractOptions,
        returnToMainChainInfo: Option[ReturnToMainChainInfo]
    ) extends State {
      def lastContractBlock: ContractBlock = chainStatus.lastContractBlock
    }

    sealed trait ChainStatus {
      def lastContractBlock: ContractBlock
    }
    object ChainStatus {
      case class FollowingChain(nodeChainInfo: ChainInfo, nextExpectedEcBlock: Option[BlockHash]) extends ChainStatus {
        override def lastContractBlock: ContractBlock = nodeChainInfo.lastBlock
      }
      case class Mining(
          keyPair: KeyPair,
          currentPayloadId: String,
          nodeChainInfo: Either[ChainSwitchInfo, ChainInfo],
          lastClToElTransferIndex: Long,
          lastElWithdrawalIndex: WithdrawalIndex
      ) extends ChainStatus {
        override def lastContractBlock: ContractBlock = nodeChainInfo match {
          case Left(chainSwitchInfo) => chainSwitchInfo.referenceBlock
          case Right(chainInfo)      => chainInfo.lastBlock
        }
      }

      case class WaitForNewChain(chainSwitchInfo: ChainSwitchInfo) extends ChainStatus {
        override def lastContractBlock: ContractBlock = chainSwitchInfo.referenceBlock
      }
    }

    case class WaitingForSyncHead(target: ContractBlock, task: CancelableFuture[BlockWithChannel]) extends State
    case class SyncingToFinalizedBlock(target: BlockHash)                                          extends State
  }

  private case class RollbackBlock(hash: BlockHash, parentBlock: EcBlock)

  case class ChainSwitchInfo(prevChainId: Long, referenceBlock: ContractBlock)

  /** We haven't received a EC-block {@link missedEcBlockHash} of a previous epoch when started a mining on a new epoch. We can return to the main
    * chain, if get a missed EC-block.
    */
  case class ReturnToMainChainInfo(missedEcBlockHash: BlockHash, missedBlockParent: EcBlock, chainId: Long)

  sealed trait BlockRequestResult
  private object BlockRequestResult {
    case class BlockExists(block: EcBlock) extends BlockRequestResult
    case class Requested(hash: BlockHash)  extends BlockRequestResult
  }

  private case class MiningData(payloadId: PayloadId, nextBlockUnixTs: Long, lastClToElTransferIndex: Long, lastElWithdrawalIndex: WithdrawalIndex)

  private case class PreValidationResult(expectReward: Boolean)

  private sealed trait BlockForValidation
  private object BlockForValidation {
    case class Found(contractBlock: ContractBlock, ecBlock: EcBlock) extends BlockForValidation
    case class SkippedFinalized(block: L2BlockLike)                  extends BlockForValidation
    case object NotFound                                             extends BlockForValidation
  }

  case class FullValidationStatus(validated: Set[BlockHash], lastElWithdrawalIndex: Option[WithdrawalIndex]) {
    // If we didn't validate the parent block last time, then the index is outdated
    def checkedLastElWithdrawalIndex(parentBlockHash: BlockHash): Option[WithdrawalIndex] =
      lastElWithdrawalIndex.filter(_ => validated.contains(parentBlockHash))
  }

  def calculateRandao(hitSource: ByteStr, parentHash: BlockHash): String = {
    val msg = hitSource.arr ++ HexBytesConverter.toBytes(parentHash)
    HexBytesConverter.toHex(crypto.secureHash(msg))
  }
}
