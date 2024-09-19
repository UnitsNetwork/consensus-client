package units

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.typesafe.scalalogging.StrictLogging
import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.merkle.Digest
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.lang.v1.compiler.Terms.FUNCTION_CALL
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
import units.ELUpdater.State.*
import units.ELUpdater.State.ChainStatus.{FollowingChain, Mining, WaitForNewChain}
import units.client.CommonBlockData
import units.client.contract.*
import units.client.engine.EngineApiClient
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.client.engine.model.Withdrawal.WithdrawalIndex
import units.eth.{EmptyPayload, EthAddress, EthereumConstants}
import units.network.BlocksObserverImpl.BlockWithChannel
import units.util.HexBytesConverter
import units.util.HexBytesConverter.toHexNoPrefix

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.*

class ELUpdater(
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
) extends StrictLogging {
  import ELUpdater.*

  private val handleNextUpdate    = SerialCancelable()
  private val contractAddress     = config.chainContractAddress
  private val chainContractClient = new ChainContractStateClient(contractAddress, blockchain)

  private[units] var state: State = Starting

  def consensusLayerChanged(): Unit =
    handleNextUpdate := scheduler.scheduleOnce(ClChangedProcessingDelay)(handleConsensusLayerChanged())

  def executionBlockReceived(block: NetworkBlock, ch: Channel): Unit = scheduler.execute { () =>
    logger.debug(s"New block ${block.hash}->${block.parentHash} (timestamp=${block.timestamp}, height=${block.height}) appeared")

    val now = time.correctedTime() / 1000
    if (block.timestamp - now <= MaxTimeDrift) {
      state match {
        case WaitingForSyncHead(target, _) if block.hash == target.hash =>
          val syncStarted = for {
            _         <- engineApiClient.applyNewPayload(block.payloadJson)
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
        case w @ Working(_, lastPayload, _, _, _, FollowingChain(nodeChainInfo, _), _, returnToMainChainInfo)
            if block.parentHash == lastPayload.hash =>
          validateAndApply(block, ch, w, lastPayload, nodeChainInfo, returnToMainChainInfo)
        case w: Working[ChainStatus] =>
          w.returnToMainChainInfo match {
            case Some(rInfo) if rInfo.missedBlock.hash == block.hash =>
              chainContractClient.getChainInfo(rInfo.chainId) match {
                case Some(chainInfo) if chainInfo.isMain =>
                  validateAndApplyMissedBlock(block, ch, w, rInfo.missedBlock, rInfo.missedBlockParentPayload, chainInfo)
                case Some(_) =>
                  logger.debug(s"Chain ${rInfo.chainId} is not main anymore, ignoring ${block.hash}")
                case _ =>
                  logger.error(s"Failed to get chain ${rInfo.chainId} info, ignoring ${block.hash}")
              }
            case _ => logger.debug(s"Expecting ${w.returnToMainChainInfo.fold("no block")(_.toString)}, ignoring unexpected ${block.hash}")
          }
        case other =>
          logger.debug(s"$other: ignoring ${block.hash}")
      }
    } else {
      logger.debug(s"Block ${block.hash} is from future: timestamp=${block.timestamp}, now=$now, Δ${block.timestamp - now}s")
    }
  }

  private def calculateEpochInfo: Either[String, EpochInfo] = {
    val epochNumber = blockchain.height
    for {
      header                 <- blockchain.blockHeader(epochNumber).toRight(s"No header at epoch $epochNumber")
      hitSource              <- blockchain.hitSource(epochNumber).toRight(s"No hit source at epoch $epochNumber")
      miner                  <- chainContractClient.calculateEpochMiner(header.header, hitSource, epochNumber, blockchain)
      rewardAddress          <- chainContractClient.getElRewardAddress(miner).toRight(s"No reward address for $miner")
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
        case tx: InvokeScriptTransaction => tx.dApp == contractAddress
        case _                           => false
      }

      if (staleTxs.nonEmpty) {
        logger.debug(s"Removing stale transactions: ${staleTxs.map(_.id()).mkString(", ")}")
        utx.removeAll(staleTxs)
      }
    }
  }

  private def callContract(fc: FUNCTION_CALL, payload: ExecutionPayload, invoker: KeyPair): JobResult[Unit] = {
    val extraFee = if (blockchain.hasPaidVerifier(invoker.toAddress)) ScriptExtraFee else 0

    val tx = InvokeScriptTransaction(
      TxVersion.V2,
      invoker.publicKey,
      contractAddress,
      Some(fc),
      Seq.empty,
      TxPositiveAmount.unsafeFrom(FeeConstants(TransactionType.InvokeScript) * FeeUnit + extraFee),
      Asset.Waves,
      time.correctedTime(),
      Proofs.empty,
      blockchain.settings.addressSchemeCharacter.toByte
    ).signWith(invoker.privateKey)
    logger.info(s"Invoking $contractAddress '${fc.function.funcName}' for block ${payload.hash}->${payload.parentHash}, txId=${tx.id()}")
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
              payloadJson <- engineApiClient.getPayloadJson(payloadId)
              _ = logger.info(s"Forged payload $payloadId")
              latestValidHashOpt <- engineApiClient.applyNewPayload(payloadJson)
              latestValidHash    <- Either.fromOption(latestValidHashOpt, ClientError("Latest valid hash not defined"))
              _ = logger.info(s"Applied payload $payloadId, block hash is $latestValidHash, timestamp = $timestamp")
              newBlock <- NetworkBlock.signed(payloadJson, m.keyPair.privateKey)
              _ = logger.debug(s"Broadcasting block ${newBlock.hash}")
              _ <- Try(allChannels.broadcast(newBlock)).toEither.leftMap(err =>
                ClientError(s"Failed to broadcast block ${newBlock.hash}: ${err.toString}")
              )
              executionPayload = newBlock.toPayload
              transfersRootHash <- getE2CTransfersRootHash(executionPayload.hash, chainContractOptions.elBridgeAddress)
              funcCall          <- contractFunction.toFunctionCall(executionPayload.hash, transfersRootHash, m.lastC2ETransferIndex)
              _                 <- callContract(funcCall, executionPayload, m.keyPair)
            } yield executionPayload).fold(
              err => logger.error(s"Failed to forge block for payloadId $payloadId at epoch ${epochInfo.number}: ${err.message}"),
              newPayload => scheduler.execute { () => tryToForgeNextBlock(epochInfo.number, newPayload, chainContractOptions) }
            )
        }
      case Working(_, _, _, _, _, _: Mining | _: FollowingChain, _, _) =>
      // a new epoch started and we trying to apply a previous epoch payload:
      // Mining - we mine again
      // FollowingChain - we validate
      case other => logger.debug(s"Unexpected state $other attempting to finish building $payloadId")
    }
  }

  private def rollbackTo(prevState: Working[ChainStatus], target: CommonBlockData, finalizedBlock: ContractBlock): JobResult[Working[ChainStatus]] = {
    val targetHash = target.hash
    for {
      rollbackBlock <- mkRollbackBlock(targetHash)
      _                   = logger.info(s"Starting rollback to $targetHash using rollback block ${rollbackBlock.hash}")
      fixedFinalizedBlock = if (finalizedBlock.height > rollbackBlock.parentPayload.height) rollbackBlock.parentPayload else finalizedBlock
      _           <- confirmBlock(rollbackBlock.hash, fixedFinalizedBlock.hash)
      _           <- confirmBlock(target, fixedFinalizedBlock)
      lastPayload <- engineApiClient.getLastPayload
      _ <- Either.cond(
        targetHash == lastPayload.hash,
        (),
        ClientError(s"Rollback to $targetHash error: last block hash ${lastPayload.hash} is not equal to target block hash")
      )
    } yield {
      logger.info(s"Rollback to $targetHash finished successfully")
      val updatedLastValidatedBlock = if (lastPayload.height < prevState.fullValidationStatus.lastValidatedBlock.height) {
        chainContractClient.getBlock(lastPayload.hash).getOrElse(finalizedBlock)
      } else {
        prevState.fullValidationStatus.lastValidatedBlock
      }
      val newState =
        prevState.copy(
          lastPayload = lastPayload,
          fullValidationStatus = FullValidationStatus(updatedLastValidatedBlock, None)
        )
      setState("10", newState)
      newState
    }
  }

  private def startBuildingPayload(
      epochInfo: EpochInfo,
      parentPayload: ExecutionPayload,
      finalizedBlock: ContractBlock,
      nextBlockUnixTs: Long,
      lastC2ETransferIndex: Long,
      lastElWithdrawalIndex: WithdrawalIndex,
      chainContractOptions: ChainContractOptions,
      prevEpochMinerRewardAddress: Option[EthAddress]
  ): JobResult[MiningData] = {
    val firstElWithdrawalIndex = lastElWithdrawalIndex + 1
    val startC2ETransferIndex  = lastC2ETransferIndex + 1

    val rewardWithdrawal = prevEpochMinerRewardAddress
      .map(Withdrawal(firstElWithdrawalIndex, _, chainContractOptions.miningReward))
      .toVector

    val transfers =
      chainContractClient
        .getNativeTransfers(
          fromIndex = startC2ETransferIndex,
          maxItems = ChainContractClient.MaxC2ETransfers - rewardWithdrawal.size
        )

    val transferWithdrawals = toWithdrawals(transfers, rewardWithdrawal.lastOption.fold(firstElWithdrawalIndex)(_.index + 1))

    val withdrawals = rewardWithdrawal ++ transferWithdrawals

    confirmBlockAndStartMining(
      parentPayload,
      finalizedBlock,
      nextBlockUnixTs,
      epochInfo.rewardAddress,
      calculateRandao(epochInfo.hitSource, parentPayload.hash),
      withdrawals
    ).map { payloadId =>
      logger.info(
        s"Starting to forge payload $payloadId by miner ${epochInfo.miner} at height ${parentPayload.height + 1} " +
          s"of epoch ${epochInfo.number} (ref=${parentPayload.hash}), ${withdrawals.size} withdrawals, ${transfers.size} transfers from $startC2ETransferIndex"
      )

      MiningData(payloadId, nextBlockUnixTs, transfers.lastOption.fold(lastC2ETransferIndex)(_.index), lastElWithdrawalIndex + withdrawals.size)
    }
  }

  private def tryToStartMining(prevState: Working[ChainStatus], nodeChainInfo: Either[ChainSwitchInfo, ChainInfo]): Unit = {
    val parentPayload = prevState.lastPayload
    val epochInfo     = prevState.epochInfo

    wallet.privateKeyAccount(epochInfo.miner) match {
      case Right(keyPair) if config.miningEnable =>
        logger.trace(s"Designated miner in epoch ${epochInfo.number} is ${epochInfo.miner}, attempting to build payload")
        val refContractBlock = nodeChainInfo match {
          case Left(chainSwitchInfo) => chainSwitchInfo.referenceBlock
          case Right(chainInfo)      => chainInfo.lastBlock
        }
        val lastC2ETransferIndex = refContractBlock.lastC2ETransferIndex

        (for {
          elWithdrawalIndexBefore <-
            parentPayload.withdrawals.lastOption.map(_.index) match {
              case Some(r) => Right(r)
              case None =>
                if (parentPayload.height - 1 <= EthereumConstants.GenesisBlockHeight) Right(-1L)
                else getLastWithdrawalIndex(parentPayload.parentHash)
            }
          nextBlockUnixTs = (parentPayload.timestamp + config.blockDelay.toSeconds).max(time.correctedTime() / 1000 + config.blockDelay.toSeconds)
          miningData <- startBuildingPayload(
            epochInfo,
            parentPayload,
            prevState.finalizedBlock,
            nextBlockUnixTs,
            lastC2ETransferIndex,
            elWithdrawalIndexBefore,
            prevState.options,
            Option.unless(parentPayload.height == EthereumConstants.GenesisBlockHeight)(parentPayload.minerRewardAddress)
          )
        } yield {
          val newState = prevState.copy(
            epochInfo = epochInfo,
            lastPayload = parentPayload,
            chainStatus = Mining(keyPair, miningData.payloadId, nodeChainInfo, miningData.lastC2ETransferIndex, miningData.lastElWithdrawalIndex)
          )

          setState("12", newState)
          scheduler.scheduleOnce((miningData.nextBlockUnixTs - time.correctedTime() / 1000).min(1).seconds)(
            prepareAndApplyPayload(
              miningData.payloadId,
              parentPayload.hash,
              miningData.nextBlockUnixTs,
              newState.options.startEpochChainFunction(parentPayload.hash, epochInfo.hitSource, nodeChainInfo.toOption),
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
      parentPayload: ExecutionPayload,
      chainContractOptions: ChainContractOptions
  ): Unit = {
    state match {
      case w @ Working(epochInfo, _, finalizedBlock, _, _, m: Mining, _, _) if epochInfo.number == epochNumber && blockchain.height == epochNumber =>
        val nextBlockUnixTs = (parentPayload.timestamp + config.blockDelay.toSeconds).max(time.correctedTime() / 1000)

        startBuildingPayload(
          epochInfo,
          parentPayload,
          finalizedBlock,
          nextBlockUnixTs,
          m.lastC2ETransferIndex,
          m.lastElWithdrawalIndex,
          chainContractOptions,
          None
        ).fold[Unit](
          err => {
            logger.error(s"Error starting payload build process: ${err.message}")
            scheduler.scheduleOnce(MiningRetryInterval) {
              tryToForgeNextBlock(epochNumber, parentPayload, chainContractOptions)
            }
          },
          miningData => {
            val newState = w.copy(
              lastPayload = parentPayload,
              chainStatus = m.copy(
                currentPayloadId = miningData.payloadId,
                lastC2ETransferIndex = miningData.lastC2ETransferIndex,
                lastElWithdrawalIndex = miningData.lastElWithdrawalIndex
              )
            )
            setState("11", newState)
            scheduler.scheduleOnce((miningData.nextBlockUnixTs - time.correctedTime() / 1000).min(1).seconds)(
              prepareAndApplyPayload(
                miningData.payloadId,
                parentPayload.hash,
                miningData.nextBlockUnixTs,
                chainContractOptions.appendFunction(parentPayload.hash),
                chainContractOptions
              )
            )
          }
        )
      case other =>
        logger.debug(s"Unexpected state $other attempting to start building block referencing ${parentPayload.hash} at epoch $epochNumber")
    }
  }

  private def updateStartingState(): Unit = {
    if (!chainContractClient.isContractSetup) logger.debug("Waiting for chain contract setup")
    else if (chainContractClient.getAllActualMiners.isEmpty) logger.debug("Waiting for at least one joined miner")
    else {
      val finalizedBlock = chainContractClient.getFinalizedBlock
      logger.debug(s"Finalized block is ${finalizedBlock.hash}")
      engineApiClient.getPayloadByHash(finalizedBlock.hash) match {
        case Left(error) => logger.error(s"Could not load finalized block payload", error)
        case Right(Some(finalizedBlockPayload)) =>
          logger.trace(s"Finalized block ${finalizedBlock.hash} is at height ${finalizedBlockPayload.height}")
          (for {
            newEpochInfo  <- calculateEpochInfo
            mainChainInfo <- chainContractClient.getMainChainInfo.toRight("Can't get main chain info")
            lastPayload   <- engineApiClient.getLastPayload.leftMap(_.message)
          } yield {
            logger.trace(s"Following main chain ${mainChainInfo.id}")
            val fullValidationStatus = FullValidationStatus(
              lastValidatedBlock = finalizedBlock,
              lastElWithdrawalIndex = None
            )
            val options = chainContractClient.getOptions
            followChainAndRequestNextBlock(
              newEpochInfo,
              mainChainInfo,
              lastPayload,
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
          logger.trace(s"Finalized block ${finalizedBlock.hash} payload is not in EC, requesting block from peers")
          setState("15", WaitingForSyncHead(finalizedBlock, requestAndProcessBlock(finalizedBlock.hash)))
      }
    }
  }

  private def handleConsensusLayerChanged(): Unit = {
    state match {
      case Starting                => updateStartingState()
      case w: Working[ChainStatus] => updateWorkingState(w)
      case other                   => logger.debug(s"Unprocessed state: $other")
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
        logger.debug(s"Found alternative chain ${chainInfo.id} referencing $referenceBlock")
        Some(chainInfo)
      case _ =>
        logger.debug(s"Not found alternative chain referencing $referenceBlock")
        None
    }
  }

  private def requestBlocksAndStartMining(prevState: Working[FollowingChain]): Unit = {
    def check(missedBlock: ContractBlock): Unit = {
      state match {
        case w @ Working(epochInfo, lastPayload, finalizedBlock, mainChainInfo, _, fc: FollowingChain, _, returnToMainChainInfo)
            if fc.nextExpectedBlock.map(_.hash).contains(missedBlock.hash) && canSupportAnotherAltChain(fc.nodeChainInfo) =>
          logger.debug(s"Block ${missedBlock.hash} wasn't received for $WaitRequestedBlockTimeout, need to switch to alternative chain")
          (for {
            lastValidBlock <- getAltChainReferenceBlock(fc.nodeChainInfo, missedBlock)
            updatedState   <- rollbackTo(w, lastValidBlock, finalizedBlock)
          } yield {
            val updatedReturnToMainChainInfo =
              if (fc.nodeChainInfo.isMain) {
                Some(ReturnToMainChainInfo(missedBlock, lastPayload, mainChainInfo.id))
              } else returnToMainChainInfo

            findAltChain(fc.nodeChainInfo.id, lastValidBlock.hash) match {
              case Some(altChainInfo) =>
                engineApiClient.getPayloadByHash(finalizedBlock.hash) match {
                  case Right(Some(finalizedBlockPayload)) =>
                    followChainAndStartMining(
                      updatedState.copy(chainStatus = FollowingChain(altChainInfo, None), returnToMainChainInfo = updatedReturnToMainChainInfo),
                      epochInfo,
                      altChainInfo.id,
                      finalizedBlockPayload,
                      finalizedBlock,
                      chainContractClient.getOptions
                    )
                  case Right(None) =>
                    logger.warn(s"Finalized block ${finalizedBlock.hash} payload is not in EC")
                  case Left(err) =>
                    logger.error(s"Could not load finalized block ${finalizedBlock.hash} payload", err)
                }
              case _ =>
                val chainSwitchInfo = ChainSwitchInfo(fc.nodeChainInfo.id, lastValidBlock)

                val newState =
                  updatedState.copy(chainStatus = WaitForNewChain(chainSwitchInfo), returnToMainChainInfo = updatedReturnToMainChainInfo)
                setState("9", newState)
                tryToStartMining(newState, Left(chainSwitchInfo))
            }
          }).fold(
            err => logger.error(err.message),
            _ => ()
          )
        case w: Working[ChainStatus] =>
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
            case _ => logger.warn(s"Unexpected Working state on mining: $w")
          }
        case other => logger.warn(s"Unexpected state on mining: $other")
      }
    }

    prevState.chainStatus.nextExpectedBlock match {
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

  private def followChainAndStartMining(
      prevState: Working[ChainStatus],
      newEpochInfo: EpochInfo,
      prevChainId: Long,
      finalizedBlockPayload: ExecutionPayload,
      finalizedBlock: ContractBlock,
      options: ChainContractOptions
  ): Unit = {
    updateToFollowChain(
      prevState,
      newEpochInfo,
      prevChainId,
      finalizedBlockPayload,
      finalizedBlock,
      options
    ).foreach { newState =>
      requestBlocksAndStartMining(newState)
    }
  }

  private def updateMiningState(prevState: Working[Mining], finalizedBlock: ContractBlock, options: ChainContractOptions): Unit = {
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
            finalizedBlock = finalizedBlock,
            mainChainInfo = mainChainInfo,
            chainStatus = prevState.chainStatus.copy(nodeChainInfo = newChainInfo.fold(prevState.chainStatus.nodeChainInfo)(Right(_))),
            options = options,
            returnToMainChainInfo =
              prevState.returnToMainChainInfo.filter(rInfo => !newChainInfo.map(_.id).contains(rInfo.chainId) && rInfo.chainId == mainChainInfo.id)
          )
        )
      case _ =>
        logger.error("Can't get main chain info")
        setState("14", Starting)
    }
  }

  private def updateWorkingState(prevState: Working[ChainStatus]): Unit = {
    val finalizedBlock = chainContractClient.getFinalizedBlock
    val options        = chainContractClient.getOptions
    logger.debug(s"Finalized block is ${finalizedBlock.hash}")
    engineApiClient.getPayloadByHash(finalizedBlock.hash) match {
      case Left(error) => logger.error(s"Could not load finalized block payload", error)
      case Right(Some(finalizedBlockPayload)) =>
        logger.trace(s"Finalized block ${finalizedBlock.hash} is at height ${finalizedBlockPayload.height}")
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
                    finalizedBlockPayload,
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
                    finalizedBlockPayload,
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
                        finalizedBlockPayload,
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
                finalizedBlockPayload,
                finalizedBlock,
                options
              )
            case m: Mining => updateMiningState(prevState.copy(chainStatus = m), finalizedBlock, options)
            case WaitForNewChain(chainSwitchInfo) =>
              val newChainInfo = findAltChain(chainSwitchInfo.prevChainId, chainSwitchInfo.referenceBlock.hash)
              newChainInfo.foreach { chainInfo =>
                updateToFollowChain(prevState, prevState.epochInfo, chainInfo.id, finalizedBlockPayload, finalizedBlock, options)
              }
          }
        }
        validateAppliedBlocks()
        requestMainChainBlock()
      case Right(None) =>
        logger.trace(s"Finalized block ${finalizedBlock.hash} payload is not in EC, requesting block from peers")
        setState("19", WaitingForSyncHead(finalizedBlock, requestAndProcessBlock(finalizedBlock.hash)))
    }
  }

  private def followChainAndRequestNextBlock(
      epochInfo: EpochInfo,
      nodeChainInfo: ChainInfo,
      lastPayload: ExecutionPayload,
      mainChainInfo: ChainInfo,
      finalizedBlock: ContractBlock,
      fullValidationStatus: FullValidationStatus,
      options: ChainContractOptions,
      returnToMainChainInfo: Option[ReturnToMainChainInfo]
  ): Working[FollowingChain] = {
    val newState = Working(
      epochInfo,
      lastPayload,
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

  private def requestBlock(contractBlock: ContractBlock): BlockRequestResult = {
    logger.debug(s"Requesting block ${contractBlock.hash}")
    engineApiClient.getPayloadByHash(contractBlock.hash) match {
      case Right(Some(payload)) => BlockRequestResult.PayloadExists(payload)
      case Right(None) =>
        requestAndProcessBlock(contractBlock.hash)
        BlockRequestResult.Requested(contractBlock)
      case Left(err) =>
        logger.warn(s"Failed to get block ${contractBlock.hash} by hash: ${err.message}")
        requestAndProcessBlock(contractBlock.hash)
        BlockRequestResult.Requested(contractBlock)
    }
  }

  private def requestMainChainBlock(): Unit = {
    state match {
      case w: Working[ChainStatus] =>
        w.returnToMainChainInfo.foreach { returnToMainChainInfo =>
          if (w.mainChainInfo.id == returnToMainChainInfo.chainId) {
            requestBlock(returnToMainChainInfo.missedBlock) match {
              case BlockRequestResult.PayloadExists(payload) =>
                logger.debug(s"Block ${returnToMainChainInfo.missedBlock.hash} payload exists at execution chain, trying to validate")
                validateAppliedBlock(returnToMainChainInfo.missedBlock, payload, w) match {
                  case Right(updatedState) =>
                    logger.debug(s"Missed block ${payload.hash} of main chain ${returnToMainChainInfo.chainId} was successfully validated")
                    chainContractClient.getChainInfo(returnToMainChainInfo.chainId) match {
                      case Some(mainChainInfo) =>
                        confirmBlockAndFollowChain(payload, updatedState, mainChainInfo, None)
                      case _ =>
                        logger.error(s"Failed to get chain ${returnToMainChainInfo.chainId} info: not found")
                    }
                  case Left(err) =>
                    logger.debug(s"Missed block ${payload.hash} of main chain ${returnToMainChainInfo.chainId} validation error: ${err.message}")
                }
              case BlockRequestResult.Requested(_) =>
            }
          }
        }
      case _ =>
    }
  }

  private def requestAndProcessBlock(hash: BlockHash): CancelableFuture[(Channel, NetworkBlock)] = {
    requestBlockFromPeers(hash).andThen {
      case Success((ch, block)) => executionBlockReceived(block, ch)
      case Failure(exception)   => logger.error(s"Error loading block $hash", exception)
    }(globalScheduler)
  }

  private def updateToFollowChain(
      prevState: Working[ChainStatus],
      epochInfo: EpochInfo,
      prevChainId: Long,
      finalizedBlockPayload: ExecutionPayload,
      finalizedContractBlock: ContractBlock,
      options: ChainContractOptions
  ): Option[Working[FollowingChain]] = {
    @tailrec
    def findLastPayload(curBlock: ContractBlock): ExecutionPayload = {
      engineApiClient.getPayloadByHash(curBlock.hash) match {
        case Right(Some(payload)) => payload
        case Right(_) =>
          chainContractClient.getBlock(curBlock.parentHash) match {
            case Some(parent) => findLastPayload(parent)
            case _ =>
              logger.warn(s"Block ${curBlock.parentHash} not found at contract")
              finalizedBlockPayload
          }
        case Left(err) =>
          logger.warn(s"Failed to get block ${curBlock.hash} payload by hash: ${err.message}")
          finalizedBlockPayload
      }
    }

    def followChain(
        nodeChainInfo: ChainInfo,
        lastPayload: ExecutionPayload,
        mainChainInfo: ChainInfo,
        fullValidationStatus: FullValidationStatus,
        returnToMainChainInfo: Option[ReturnToMainChainInfo]
    ): Working[FollowingChain] = {
      val newState = Working(
        epochInfo,
        lastPayload,
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
        target: CommonBlockData,
        nodeChainInfo: ChainInfo,
        mainChainInfo: ChainInfo,
        returnToMainChainInfo: Option[ReturnToMainChainInfo]
    ): Option[Working[FollowingChain]] = {
      rollbackTo(prevState, target, finalizedContractBlock) match {
        case Right(updatedState) =>
          Some(followChain(nodeChainInfo, updatedState.lastPayload, mainChainInfo, updatedState.fullValidationStatus, returnToMainChainInfo))
        case Left(err) =>
          logger.error(s"Failed to rollback to ${target.hash}: ${err.message}")
          None
      }
    }

    def rollbackAndFollowMainChain(target: CommonBlockData, mainChainInfo: ChainInfo): Option[Working[FollowingChain]] =
      rollbackAndFollowChain(target, mainChainInfo, mainChainInfo, None)

    (chainContractClient.getMainChainInfo, chainContractClient.getChainInfo(prevChainId)) match {
      case (Some(mainChainInfo), Some(prevChainInfo)) =>
        if (mainChainInfo.id != prevState.mainChainInfo.id) {
          val updatedLastPayload = findLastPayload(mainChainInfo.lastBlock)
          rollbackAndFollowMainChain(updatedLastPayload, mainChainInfo)
        } else if (prevChainInfo.firstBlock.height < finalizedContractBlock.height && !prevChainInfo.isMain) {
          val targetBlockHash = prevChainInfo.firstBlock.parentHash
          chainContractClient.getBlock(targetBlockHash) match {
            case Some(targetBlock) => rollbackAndFollowMainChain(targetBlock, mainChainInfo)
            case None =>
              logger.error(s"Failed to get block $targetBlockHash meta at contract")
              None
          }
        } else if (isLastPayloadOnFork(prevChainInfo, prevState.lastPayload)) {
          val updatedLastPayload = findLastPayload(prevChainInfo.lastBlock)
          rollbackAndFollowChain(updatedLastPayload, prevChainInfo, mainChainInfo, prevState.returnToMainChainInfo)
        } else {
          Some(followChain(prevChainInfo, prevState.lastPayload, mainChainInfo, prevState.fullValidationStatus, prevState.returnToMainChainInfo))
        }
      case (Some(mainChainInfo), None) =>
        rollbackAndFollowMainChain(finalizedBlockPayload, mainChainInfo)
      case (None, _) =>
        logger.error("Failed to get main chain info")
        None
    }
  }

  private def isLastPayloadOnFork(chainInfo: ChainInfo, lastPayload: ExecutionPayload) =
    chainInfo.lastBlock.height == lastPayload.height && chainInfo.lastBlock.hash != lastPayload.hash ||
      chainInfo.lastBlock.height > lastPayload.height && !chainContractClient.blockExists(lastPayload.hash) ||
      chainInfo.lastBlock.height < lastPayload.height

  private def waitForSyncCompletion(target: ContractBlock): Unit = scheduler.scheduleOnce(5.seconds)(state match {
    case SyncingToFinalizedBlock(finalizedBlockHash) if finalizedBlockHash == target.hash =>
      logger.debug(s"Checking if EL has synced to ${target.hash} on height ${target.height}")
      engineApiClient.getLastPayload match {
        case Left(error) =>
          logger.error(s"Sync to ${target.hash} was not completed, error=${error.message}")
          setState("23", Starting)
        case Right(lastPayload) if lastPayload.hash == target.hash =>
          logger.debug(s"Finished synchronization to ${target.hash} successfully")
          calculateEpochInfo match {
            case Left(err) =>
              logger.error(s"Could not transition to following chain state: $err")
              setState("24", Starting)
            case Right(newEpochInfo) =>
              chainContractClient.getMainChainInfo match {
                case Some(mainChainInfo) =>
                  logger.trace(s"Following main chain ${mainChainInfo.id}")
                  val fullValidationStatus =
                    FullValidationStatus(
                      lastValidatedBlock = target,
                      lastElWithdrawalIndex = None
                    )
                  followChainAndRequestNextBlock(
                    newEpochInfo,
                    mainChainInfo,
                    lastPayload,
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
        case Right(lastPayload) =>
          logger.debug(s"Sync to ${target.hash} is in progress: current last block is ${lastPayload.hash} at height ${lastPayload.height}")
          waitForSyncCompletion(target)
      }
    case other =>
      logger.debug(s"Unexpected state on sync: $other")
  })

  private def validateRandao(payload: ExecutionPayload, epochNumber: Int): JobResult[Unit] =
    blockchain.vrf(epochNumber) match {
      case None => ClientError(s"VRF of $epochNumber epoch is empty").asLeft
      case Some(vrf) =>
        val expectedPrevRandao = calculateRandao(vrf, payload.parentHash)
        Either.cond(
          expectedPrevRandao == payload.prevRandao,
          (),
          ClientError(s"expected prevRandao $expectedPrevRandao, got ${payload.prevRandao}, VRF=$vrf of $epochNumber")
        )
    }

  private def validateMiner(block: NetworkBlock, epochInfo: Option[EpochInfo]): JobResult[Unit] = {
    epochInfo match {
      case Some(epochMeta) =>
        for {
          _ <- Either.cond(
            block.minerRewardAddress == epochMeta.rewardAddress,
            (),
            ClientError(s"block miner ${block.minerRewardAddress} doesn't equal to ${epochMeta.rewardAddress}")
          )
          signature <- Either.fromOption(block.signature, ClientError(s"signature not found"))
          publicKey <- Either.fromOption(
            chainContractClient.getMinerPublicKey(block.minerRewardAddress),
            ClientError(s"public key for block miner ${block.minerRewardAddress} not found")
          )
          _ <- Either.cond(
            crypto.verify(signature, Json.toBytes(block.payloadJson), publicKey, checkWeakPk = true),
            (),
            ClientError(s"invalid signature")
          )
        } yield ()
      case _ => Either.unit
    }
  }

  private def validateTimestamp(block: NetworkBlock, parentPayload: ExecutionPayload): JobResult[Unit] = {
    val minAppendTs = parentPayload.timestamp + config.blockDelay.toSeconds
    Either.cond(
      block.timestamp >= minAppendTs,
      (),
      ClientError(
        s"timestamp (${block.timestamp}) of appended block must be greater or equal $minAppendTs, " +
          s"Δ${minAppendTs - block.timestamp}s"
      )
    )
  }

  private def preValidateBlock(
      block: NetworkBlock,
      parentPayload: ExecutionPayload,
      epochInfo: Option[EpochInfo]
  ): JobResult[Unit] = {
    for {
      _ <- validateTimestamp(block, parentPayload)
      _ <- validateMiner(block, epochInfo)
      _ <- engineApiClient.applyNewPayload(block.payloadJson)
    } yield ()
  }

  private def getAltChainReferenceBlock(nodeChainInfo: ChainInfo, lastContractBlock: ContractBlock): JobResult[ContractBlock] = {
    if (nodeChainInfo.isMain) {
      for {
        lastEpoch <- chainContractClient
          .getEpochMeta(lastContractBlock.epoch)
          .toRight(
            ClientError(
              s"Can't find the epoch #${lastContractBlock.epoch} metadata of invalid block ${lastContractBlock.hash} on contract"
            )
          )
        prevEpoch <- chainContractClient
          .getEpochMeta(lastEpoch.prevEpoch)
          .toRight(ClientError(s"Can't find a previous epoch #${lastEpoch.prevEpoch} metadata on contract"))
        referenceBlockHash = prevEpoch.lastBlockHash
        referenceBlock <- chainContractClient
          .getBlock(referenceBlockHash)
          .toRight(ClientError(s"Can't find a last block $referenceBlockHash of epoch #${lastEpoch.prevEpoch} on contract"))
      } yield referenceBlock
    } else {
      val blockId = nodeChainInfo.firstBlock.parentHash
      chainContractClient
        .getBlock(blockId)
        .toRight(
          ClientError(s"Parent block $blockId for first block ${nodeChainInfo.firstBlock.hash} of chain ${nodeChainInfo.id} not found at contract")
        )
    }
  }

  private def validateAndApplyMissedBlock(
      block: NetworkBlock,
      ch: Channel,
      prevState: Working[ChainStatus],
      contractBlock: ContractBlock,
      parentPayload: ExecutionPayload,
      nodeChainInfo: ChainInfo
  ): Unit = {
    validateBlockFull(block, contractBlock, parentPayload, prevState) match {
      case Right(updatedState) =>
        logger.debug(s"Missed block ${block.hash} of main chain ${nodeChainInfo.id} was successfully validated")
        broadcastAndConfirmBlock(block, ch, updatedState, nodeChainInfo, None)
      case Left(err) =>
        logger.debug(s"Missed block ${block.hash} of main chain ${nodeChainInfo.id} validation error: ${err.message}, ignoring block")
    }
  }

  private def validateAndApply(
      block: NetworkBlock,
      ch: Channel,
      prevState: Working[ChainStatus],
      parentPayload: ExecutionPayload,
      nodeChainInfo: ChainInfo,
      returnToMainChainInfo: Option[ReturnToMainChainInfo]
  ): Unit = {
    chainContractClient.getBlock(block.hash) match {
      case Some(contractBlock) if prevState.fullValidationStatus.lastValidatedBlock.hash == parentPayload.hash =>
        // all blocks before current was fully validated, so we can perform full validation of this block
        validateBlockFull(block, contractBlock, parentPayload, prevState) match {
          case Right(updatedState) =>
            logger.debug(s"Block ${block.hash} was successfully validated")
            broadcastAndConfirmBlock(block, ch, updatedState, nodeChainInfo, returnToMainChainInfo)
          case Left(err) =>
            logger.debug(s"Block ${block.hash} validation error: ${err.message}")
            processInvalidBlock(contractBlock, prevState, Some(nodeChainInfo))
        }
      case contractBlock =>
        // we should check block miner based on epochInfo if block is not at contract yet
        val epochInfo = if (contractBlock.isEmpty) Some(prevState.epochInfo) else None

        preValidateBlock(block, parentPayload, epochInfo) match {
          case Right(_) =>
            logger.debug(s"Block ${block.hash} was successfully partially validated")
            broadcastAndConfirmBlock(block, ch, prevState, nodeChainInfo, returnToMainChainInfo)
          case Left(err) =>
            logger.error(s"Block ${block.hash} prevalidation error: ${err.message}, ignoring block")
        }
    }
  }

  private def confirmBlockAndFollowChain(
      payload: ExecutionPayload,
      prevState: Working[ChainStatus],
      nodeChainInfo: ChainInfo,
      returnToMainChainInfo: Option[ReturnToMainChainInfo]
  ): Unit = {
    val finalizedBlock = prevState.finalizedBlock
    confirmBlock(payload, finalizedBlock)
      .fold[Unit](
        err => logger.error(s"Can't confirm block ${payload.hash} of chain ${nodeChainInfo.id}: ${err.message}"),
        _ => {
          logger.info(s"Successfully confirmed block ${payload.hash} of chain ${nodeChainInfo.id}")
          followChainAndRequestNextBlock(
            prevState.epochInfo,
            nodeChainInfo,
            payload,
            prevState.mainChainInfo,
            finalizedBlock,
            prevState.fullValidationStatus,
            prevState.options,
            returnToMainChainInfo
          )
          validateAppliedBlocks()
        }
      )
  }

  private def broadcastAndConfirmBlock(
      block: NetworkBlock,
      ch: Channel,
      prevState: Working[ChainStatus],
      nodeChainInfo: ChainInfo,
      returnToMainChainInfo: Option[ReturnToMainChainInfo]
  ): Unit = {
    Try(allChannels.broadcast(block, Some(ch))).recover { err =>
      logger.error(s"Failed to broadcast block ${block.hash}: ${err.getMessage}")
    }

    confirmBlockAndFollowChain(block.toPayload, prevState, nodeChainInfo, returnToMainChainInfo)
  }

  private def findBlockChild(parent: BlockHash, lastBlockHash: BlockHash): Either[String, ContractBlock] = {
    @tailrec
    def loop(b: BlockHash): Option[ContractBlock] = chainContractClient.getBlock(b) match {
      case None => None
      case Some(cb) =>
        if (cb.parentHash == parent) Some(cb)
        else loop(cb.parentHash)
    }

    loop(lastBlockHash).toRight(s"Could not find child of $parent")
  }

  @tailrec
  private def maybeRequestNextBlock(prevState: Working[FollowingChain], finalizedBlock: ContractBlock): Working[FollowingChain] = {
    if (prevState.lastPayload.height < prevState.chainStatus.nodeChainInfo.lastBlock.height) {
      logger.debug(s"EC chain is not synced, trying to find next block to request")
      findBlockChild(prevState.lastPayload.hash, prevState.chainStatus.nodeChainInfo.lastBlock.hash) match {
        case Left(error) =>
          logger.error(s"Could not find child of ${prevState.lastPayload.hash} on contract: $error")
          prevState
        case Right(contractBlock) =>
          requestBlock(contractBlock) match {
            case BlockRequestResult.PayloadExists(payload) =>
              logger.debug(s"Block ${contractBlock.hash} payload exists at EC chain, trying to confirm")
              confirmBlock(payload, finalizedBlock) match {
                case Right(_) =>
                  val newState = prevState.copy(
                    lastPayload = payload,
                    chainStatus = FollowingChain(prevState.chainStatus.nodeChainInfo, None)
                  )
                  setState("7", newState)
                  maybeRequestNextBlock(newState, finalizedBlock)
                case Left(err) =>
                  logger.error(s"Failed to confirm next block ${payload.hash}: ${err.message}")
                  prevState
              }
            case BlockRequestResult.Requested(contractBlock) =>
              val newState = prevState.copy(chainStatus = prevState.chainStatus.copy(nextExpectedBlock = Some(contractBlock)))
              setState("8", newState)
              newState
          }
      }
    } else {
      logger.trace(s"EC chain ${prevState.chainStatus.nodeChainInfo.id} is synced, no need to request blocks")
      prevState
    }
  }

  private def mkRollbackBlock(rollbackTargetBlockId: BlockHash): JobResult[RollbackBlock] = for {
    targetBlockDataOpt <- chainContractClient.getBlock(rollbackTargetBlockId) match {
      case None => engineApiClient.getPayloadByHash(rollbackTargetBlockId)
      case x    => Right(x)
    }
    targetBlockData <- Either.fromOption(targetBlockDataOpt, ClientError(s"Can't find block $rollbackTargetBlockId neither on a contract, nor in EC"))
    parentPayloadOpt <- engineApiClient.getPayloadByHash(targetBlockData.parentHash)
    parentPayload <- Either.fromOption(parentPayloadOpt, ClientError(s"Can't find block $rollbackTargetBlockId parent payload in execution client"))
    rollbackBlockOpt <- engineApiClient.applyNewPayload(EmptyPayload.mkExecutionPayloadJson(parentPayload))
    rollbackBlock    <- Either.fromOption(rollbackBlockOpt, ClientError("Rollback block hash is not defined as latest valid hash"))
  } yield RollbackBlock(rollbackBlock, parentPayload)

  private def toWithdrawals(transfers: Vector[ChainContractClient.ContractTransfer], firstWithdrawalIndex: Long): Vector[Withdrawal] =
    transfers.zipWithIndex.map { case (x, i) =>
      val index = firstWithdrawalIndex + i
      Withdrawal(index, x.destElAddress, Bridge.clToGweiNativeTokenAmount(x.amount))
    }

  private def getLastWithdrawalIndex(hash: BlockHash): JobResult[WithdrawalIndex] =
    engineApiClient.getPayloadByHash(hash).flatMap {
      case None => Left(ClientError(s"Can't find block $hash payload on EC during withdrawal search"))
      case Some(payload) =>
        payload.withdrawals.lastOption match {
          case Some(lastWithdrawal) => Right(lastWithdrawal.index)
          case None =>
            if (payload.height == 0) Right(-1L)
            else getLastWithdrawalIndex(payload.parentHash)
        }
    }

  private def getE2CTransfersRootHash(hash: BlockHash, elBridgeAddress: EthAddress): JobResult[Digest] =
    for {
      elRawLogs <- engineApiClient.getLogs(hash, elBridgeAddress, Bridge.ElSentNativeEventTopic)
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

  private def skipFinalizedBlocksValidation(curState: Working[ChainStatus]) = {
    if (curState.finalizedBlock.height > curState.fullValidationStatus.lastValidatedBlock.height) {
      val newState = curState.copy(fullValidationStatus = FullValidationStatus(curState.finalizedBlock, None))
      setState("4", newState)
      newState
    } else curState
  }

  private def validateAppliedBlocks(): Unit = {
    state match {
      case w: Working[ChainStatus] =>
        val startState = skipFinalizedBlocksValidation(w)
        getContractBlocksForValidation(startState).fold[Unit](
          err => logger.error(s"Validation of applied blocks error: ${err.message}"),
          blocksToValidate =>
            blocksToValidate.foldLeft[JobResult[Working[ChainStatus]]](Right(startState)) {
              case (Right(curState), block) =>
                logger.debug(s"Trying to validate applied block ${block.hash}")
                validateAppliedBlock(block.contractBlock, block.payload, curState) match {
                  case Right(updatedState) =>
                    logger.debug(s"Block ${block.hash} was successfully validated")
                    Right(updatedState)
                  case Left(err) =>
                    logger.debug(s"Validation of applied block ${block.hash} failed: ${err.message}")
                    processInvalidBlock(block.contractBlock, curState, None)
                    Left(err)
                }
              case (err, _) => err
            }
        )
      case other =>
        logger.debug(s"Skipping validation of applied blocks: $other")
        Either.unit
    }
  }

  private def validateE2CTransfers(contractBlock: ContractBlock, elBridgeAddress: EthAddress): JobResult[Unit] =
    getE2CTransfersRootHash(contractBlock.hash, elBridgeAddress).flatMap { elRootHash =>
      // elRootHash is the source of true
      if (java.util.Arrays.equals(contractBlock.e2cTransfersRootHash, elRootHash)) Either.unit
      else
        Left(
          ClientError(
            s"EL to CL transfers hash of ${contractBlock.hash} are different: " +
              s"EL=${toHexNoPrefix(elRootHash)}, " +
              s"CL=${toHexNoPrefix(contractBlock.e2cTransfersRootHash)}"
          )
        )
    }

  private def validateWithdrawals(
      contractBlock: ContractBlock,
      payload: ExecutionPayload,
      fullValidationStatus: FullValidationStatus,
      chainContractOptions: ChainContractOptions
  ): JobResult[Option[WithdrawalIndex]] = {
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
    val prevMinerElRewardAddress = if (expectMiningReward) chainContractClient.getElRewardAddress(blockPrevEpoch.miner) else None

    for {
      elWithdrawalIndexBefore <- fullValidationStatus.checkedLastElWithdrawalIndex(payload.parentHash) match {
        case Some(r) => Right(r)
        case None =>
          if (payload.height - 1 <= EthereumConstants.GenesisBlockHeight) Right(-1L)
          else getLastWithdrawalIndex(payload.parentHash)
      }
      lastElWithdrawalIndex <- validateC2ETransfers(
        payload,
        contractBlock,
        prevMinerElRewardAddress,
        chainContractOptions,
        elWithdrawalIndexBefore
      )
        .leftMap(ClientError.apply)
    } yield Some(lastElWithdrawalIndex)
  }

  private def validateBlockFull(
      block: NetworkBlock,
      contractBlock: ContractBlock,
      parentPayload: ExecutionPayload,
      prevState: Working[ChainStatus]
  ): JobResult[Working[ChainStatus]] = {
    logger.debug(s"Trying to do full validation of block ${block.hash}")
    for {
      _ <- preValidateBlock(block, parentPayload, None)
      payload = block.toPayload
      updatedState <- validateAppliedBlock(contractBlock, payload, prevState)
    } yield updatedState
  }

  // Note: we can not do this validation before block application, because we need block logs
  private def validateAppliedBlock(
      contractBlock: ContractBlock,
      payload: ExecutionPayload,
      prevState: Working[ChainStatus]
  ): JobResult[Working[ChainStatus]] = {
    val validationResult =
      for {
        _ <- Either.cond(
          contractBlock.minerRewardAddress == payload.minerRewardAddress,
          (),
          ClientError(
            s"Miner in block payload (${payload.minerRewardAddress}) should be equal to miner on contract (${contractBlock.minerRewardAddress})"
          )
        )
        _                            <- validateE2CTransfers(contractBlock, prevState.options.elBridgeAddress)
        updatedLastElWithdrawalIndex <- validateWithdrawals(contractBlock, payload, prevState.fullValidationStatus, prevState.options)
        _                            <- validateRandao(payload, contractBlock.epoch)
      } yield updatedLastElWithdrawalIndex

    validationResult.map { lastElWithdrawalIndex =>
      val newState = prevState.copy(fullValidationStatus =
        FullValidationStatus(
          lastValidatedBlock = contractBlock,
          lastElWithdrawalIndex = lastElWithdrawalIndex
        )
      )
      setState("5", newState)
      newState
    }
  }

  private def processInvalidBlock(
      contractBlock: ContractBlock,
      prevState: Working[ChainStatus],
      nodeChainInfo: Option[ChainInfo]
  ): Unit = {
    nodeChainInfo.orElse(chainContractClient.getChainInfo(contractBlock.chainId)) match {
      case Some(chainInfo) if canSupportAnotherAltChain(chainInfo) =>
        (for {
          referenceBlock <- getAltChainReferenceBlock(chainInfo, contractBlock)
          updatedState   <- rollbackTo(prevState, referenceBlock, prevState.finalizedBlock)
          lastValidBlock <- chainContractClient
            .getBlock(updatedState.lastPayload.hash)
            .toRight(ClientError(s"Block ${updatedState.lastPayload.hash} not found at contract"))
        } yield {
          findAltChain(chainInfo.id, lastValidBlock.hash) match {
            case Some(altChainInfo) =>
              val newState = updatedState.copy(
                chainStatus = FollowingChain(altChainInfo, None),
                returnToMainChainInfo = if (chainInfo.isMain) None else updatedState.returnToMainChainInfo
              )
              setState("20", newState)
              newState
            case _ =>
              val newState = updatedState.copy(
                chainStatus = WaitForNewChain(ChainSwitchInfo(chainInfo.id, lastValidBlock)),
                returnToMainChainInfo = if (chainInfo.isMain) None else updatedState.returnToMainChainInfo
              )
              setState("21", newState)
              newState
          }
        }).fold(
          err => logger.error(err.message),
          _ => ()
        )
      case Some(_) =>
        logger.debug(s"Can't support another alt chain: ignoring invalid block ${contractBlock.hash}")
      case _ =>
        logger.error(s"Chain ${contractBlock.chainId} meta not found at contract")
    }
  }

  private def getContractBlocksForValidation(curState: Working[ChainStatus]): JobResult[List[BlockForValidation]] = {
    @tailrec
    def loop(curBlock: ContractBlock, acc: List[BlockForValidation]): JobResult[List[BlockForValidation]] = {
      if (curBlock.height <= curState.fullValidationStatus.lastValidatedBlock.height || curBlock.height <= curState.finalizedBlock.height) {
        Right(acc)
      } else {
        chainContractClient.getBlock(curBlock.parentHash) match {
          case Some(parentBlock) =>
            if (curBlock.height > curState.lastPayload.height) {
              loop(parentBlock, acc)
            } else {
              engineApiClient.getPayloadByHash(curBlock.hash) match {
                case Right(Some(payload)) =>
                  loop(parentBlock, BlockForValidation(curBlock, payload) :: acc)
                case Right(None) =>
                  Left(ClientError(s"Block ${curBlock.hash} payload not found on EC client for full validation"))
                case Left(err) =>
                  Left(ClientError(s"Can't get block ${curBlock.hash} payload for full validation: ${err.message}"))
              }
            }
          case _ =>
            Left(ClientError(s"Block ${curBlock.parentHash} not found at contract during full validation"))
        }
      }
    }

    loop(curState.lastContractBlock, List.empty)
  }

  private def validateC2ETransfers(
      payload: ExecutionPayload,
      contractBlock: ContractBlock,
      prevMinerElRewardAddress: Option[EthAddress],
      options: ChainContractOptions,
      elWithdrawalIndexBefore: WithdrawalIndex
  ): Either[String, WithdrawalIndex] = {
    val parentContractBlock = chainContractClient
      .getBlock(contractBlock.parentHash)
      .getOrElse(throw new RuntimeException(s"Can't find a parent block ${contractBlock.parentHash} of block ${contractBlock.hash}"))

    val expectedTransfers = chainContractClient.getNativeTransfers(
      parentContractBlock.lastC2ETransferIndex + 1,
      contractBlock.lastC2ETransferIndex - parentContractBlock.lastC2ETransferIndex
    )

    val firstWithdrawalIndex = elWithdrawalIndexBefore + 1
    for {
      expectedWithdrawals <- prevMinerElRewardAddress match {
        case None =>
          if (payload.withdrawals.size == expectedTransfers.size) toWithdrawals(expectedTransfers, firstWithdrawalIndex).asRight
          else s"Expected ${expectedTransfers.size} withdrawals, got ${payload.withdrawals.size}".asLeft

        case Some(prevMinerElRewardAddress) =>
          if (payload.withdrawals.size == expectedTransfers.size + 1) { // +1 for reward
            val rewardWithdrawal = Withdrawal(firstWithdrawalIndex, prevMinerElRewardAddress, options.miningReward)
            val userWithdrawals  = toWithdrawals(expectedTransfers, rewardWithdrawal.index + 1)

            (rewardWithdrawal +: userWithdrawals).asRight
          } else s"Expected ${expectedTransfers.size + 1} (at least reward) withdrawals, got ${payload.withdrawals.size}".asLeft
      }
      _ <- validateC2ETransfers(payload, expectedWithdrawals)
    } yield expectedWithdrawals.lastOption.fold(elWithdrawalIndexBefore)(_.index)
  }

  private def validateC2ETransfers(payload: ExecutionPayload, expectedWithdrawals: Seq[Withdrawal]): Either[String, Unit] =
    payload.withdrawals
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

  private def confirmBlock(blockData: CommonBlockData, finalizedBlockData: CommonBlockData): JobResult[PayloadStatus] = {
    val finalizedBlockHash = if (finalizedBlockData.height > blockData.height) blockData.hash else finalizedBlockData.hash
    engineApiClient.forkChoiceUpdate(blockData.hash, finalizedBlockHash)
  }

  private def confirmBlock(hash: BlockHash, finalizedBlockHash: BlockHash): JobResult[PayloadStatus] =
    engineApiClient.forkChoiceUpdate(hash, finalizedBlockHash)

  private def confirmBlockAndStartMining(
      lastPayload: ExecutionPayload,
      finalizedBlock: ContractBlock,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal]
  ): JobResult[PayloadId] = {
    val finalizedBlockHash = if (finalizedBlock.height > lastPayload.height) lastPayload.hash else finalizedBlock.hash
    engineApiClient
      .forkChoiceUpdateWithPayloadId(
        lastPayload.hash,
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
  private val MaxTimeDrift: Int                       = 1 // second
  val WaitForReferenceConfirmInterval: FiniteDuration = 500.millis
  val ClChangedProcessingDelay: FiniteDuration        = 50.millis
  val MiningRetryInterval: FiniteDuration             = 5.seconds
  val WaitRequestedBlockTimeout: FiniteDuration       = 2.seconds

  case class EpochInfo(number: Int, miner: Address, rewardAddress: EthAddress, hitSource: ByteStr, prevEpochLastBlockHash: Option[BlockHash])

  sealed trait State
  object State {
    case object Starting extends State

    case class Working[+CS <: ChainStatus](
        epochInfo: EpochInfo,
        lastPayload: ExecutionPayload,
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
      case class FollowingChain(nodeChainInfo: ChainInfo, nextExpectedBlock: Option[ContractBlock]) extends ChainStatus {
        override def lastContractBlock: ContractBlock = nodeChainInfo.lastBlock
      }
      case class Mining(
          keyPair: KeyPair,
          currentPayloadId: String,
          nodeChainInfo: Either[ChainSwitchInfo, ChainInfo],
          lastC2ETransferIndex: Long,
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

  private case class RollbackBlock(hash: BlockHash, parentPayload: ExecutionPayload)

  case class ChainSwitchInfo(prevChainId: Long, referenceBlock: ContractBlock)

  /** We haven't received a EC-block {@link missedBlock} of a previous epoch when started a mining on a new epoch. We can return to the main chain, if
    * get a missed EC-block.
    */
  case class ReturnToMainChainInfo(missedBlock: ContractBlock, missedBlockParentPayload: ExecutionPayload, chainId: Long)

  sealed trait BlockRequestResult
  private object BlockRequestResult {
    case class PayloadExists(payload: ExecutionPayload) extends BlockRequestResult
    case class Requested(contractBlock: ContractBlock)  extends BlockRequestResult
  }

  private case class MiningData(payloadId: PayloadId, nextBlockUnixTs: Long, lastC2ETransferIndex: Long, lastElWithdrawalIndex: WithdrawalIndex)

  private case class BlockForValidation(contractBlock: ContractBlock, payload: ExecutionPayload) {
    val hash: BlockHash = contractBlock.hash
  }

  case class FullValidationStatus(lastValidatedBlock: ContractBlock, lastElWithdrawalIndex: Option[WithdrawalIndex]) {
    // If we didn't validate the parent block last time, then the index is outdated
    def checkedLastElWithdrawalIndex(parentBlockHash: BlockHash): Option[WithdrawalIndex] =
      lastElWithdrawalIndex.filter(_ => parentBlockHash == lastValidatedBlock.hash)
  }

  def calculateRandao(hitSource: ByteStr, parentHash: BlockHash): String = {
    val msg = hitSource.arr ++ HexBytesConverter.toBytes(parentHash)
    HexBytesConverter.toHex(crypto.secureHash(msg))
  }
}
