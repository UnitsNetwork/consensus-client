package units

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.typesafe.scalalogging.StrictLogging
import com.wavesplatform.account.{Address, AddressScheme, KeyPair, PKKeyPair}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.lang.v1.compiler.Terms.FUNCTION_CALL
import com.wavesplatform.network.ChannelGroupExt
import com.wavesplatform.state.diffs.FeeValidation.{FeeConstants, FeeUnit, ScriptExtraFee}
import com.wavesplatform.state.diffs.TransactionDiffer.TransactionValidationError
import com.wavesplatform.state.{Blockchain, BooleanDataEntry}
import com.wavesplatform.transaction.TxValidationError.InvokeRejectError
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.smart.script.trace.TracedResult
import com.wavesplatform.transaction.{Asset, Proofs, Transaction, TransactionSignOps, TransactionType, TxPositiveAmount, TxVersion}
import com.wavesplatform.utils.{Time, UnsupportedFeature, forceStopApplication}
import com.wavesplatform.wallet.Wallet
import io.netty.channel.Channel
import io.netty.channel.group.DefaultChannelGroup
import kamon.Kamon
import monix.execution.cancelables.SerialCancelable
import monix.execution.{CancelableFuture, Scheduler}
import play.api.libs.json.*
import units.ELUpdater.State.*
import units.ELUpdater.State.ChainStatus.{FollowingChain, Mining, WaitForNewChain}
import units.client.L2BlockLike
import units.client.contract.*
import units.client.contract.ChainContractClient.ContractTransfer
import units.client.engine.EngineApiClient
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.client.engine.model.Withdrawal.WithdrawalIndex
import units.el.*
import units.eth.*
import units.network.BlocksObserverImpl.BlockWithChannel
import units.util.HexBytesConverter
import units.util.HexBytesConverter.toHexNoPrefix

import java.math.BigInteger
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.*

class ELUpdater(
    engineApiClient: EngineApiClient,
    blockchain: Blockchain,
    allChannels: DefaultChannelGroup,
    config: ClientConfig,
    time: Time,
    wallet: Wallet,
    registryAddress: Option[Address],
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
    handleNextUpdate := scheduler.scheduleOnceLabeled("handleConsensusLayerChanged", ClChangedProcessingDelay)(handleConsensusLayerChanged())

  def executionBlockReceived(block: NetworkL2Block, ch: Channel): Unit = scheduler.execute { () =>
    logger.debug(s"New block ${block.hash}->${block.parentHash} (timestamp=${block.timestamp}, height=${block.height}) appeared")

    val now = time.correctedTime() / 1000
    if (block.timestamp - now <= MaxTimeDrift) {
      state match {
        case WaitingForSyncHead(target, _) if block.hash == target.hash =>
          val syncStarted = for {
            _         <- engineApiClient.newPayload(block.payload)
            fcuStatus <- confirmBlock(target, target)
          } yield fcuStatus

          syncStarted match {
            case Left(value) =>
              logger.error(s"Error starting sync: $value")
              setState("executionBlockReceived, error", Starting)
            case Right(fcuStatus) =>
              setState("executionBlockReceived, sync", SyncingToFinalizedBlock(target.hash))
              logger.debug(s"Waiting for sync completion: $fcuStatus")
              waitForSyncCompletion(target)
          }
        case w @ Working(chainStatus = FollowingChain(nodeChainInfo, _)) if block.parentHash == w.lastEcBlock.hash =>
          validateAndApply(block, ch, w, w.lastEcBlock, nodeChainInfo, w.returnToMainChainInfo)
        case w: Working[ChainStatus] =>
          w.returnToMainChainInfo match {
            case Some(rInfo) if rInfo.missedBlock.hash == block.hash =>
              chainContractClient.getChainInfo(rInfo.chainId) match {
                case Some(chainInfo) if chainInfo.isMain =>
                  validateAndApplyMissedBlock(block, ch, w, rInfo.missedBlock, rInfo.missedBlockParent, chainInfo)
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

  private def callContract(fc: FUNCTION_CALL, invoker: KeyPair): JobResult[Unit] = {
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
      AddressScheme.current.chainId
    ).signWith(invoker.privateKey)
    logger.info(
      s"Invoking $contractAddress '${fc.function.funcName}'(${fc.args.mkString(", ")}), txId=${tx.id()}"
    )

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

  private def rollbackDryRun(prevState: Working[ChainStatus], target: L2BlockLike, finalizedBlock: ContractBlock): JobResult[Working[ChainStatus]] = {
    val targetHash = target.hash
    for {
      lastEcBlock <- engineApiClient.getBlockByHash(targetHash).flatMap(_.toRight(ClientError(s"Block $targetHash was not found")))
    } yield {
      logger.info(s"Ready for rollback to $targetHash at height ${lastEcBlock.height}")
      val updatedLastValidatedBlock = if (lastEcBlock.height < prevState.fullValidationStatus.lastValidatedBlock.height) {
        chainContractClient.getBlock(lastEcBlock.hash).getOrElse(finalizedBlock)
      } else {
        prevState.fullValidationStatus.lastValidatedBlock
      }
      val newState =
        prevState.copy(
          lastEcBlock = lastEcBlock,
          fullValidationStatus = FullValidationStatus(updatedLastValidatedBlock, None)
        )
      setState("prepareForRollback", newState)
      newState
    }
  }

  // Also switches the head in EC
  private def updateHeadAndStartBuildingPayload(
      epochInfo: EpochInfo,
      parentBlock: EcBlock,
      finalizedBlock: ContractBlock,
      nextBlockUnixTs: Long,
      lastC2ETransferIndex: WithdrawalIndex,
      lastElWithdrawalIndex: WithdrawalIndex,
      lastAssetRegistryIndex: Int,
      chainContractOptions: ChainContractOptions,
      prevEpochMinerRewardAddress: Option[EthAddress],
      willSimulateBlock: Boolean
  ): JobResult[MiningData] = {
    val startElWithdrawalIndex = lastElWithdrawalIndex + 1
    val startC2ETransferIndex  = lastC2ETransferIndex + 1

    val rewardWithdrawal = prevEpochMinerRewardAddress
      .map(Withdrawal(startElWithdrawalIndex, _, chainContractOptions.miningReward))
      .toVector

    val strictC2ETransfersActivated = epochInfo.number >= chainContractClient.getStrictC2ETransfersActivationEpoch

    val transfers = chainContractClient.getTransfersForPayload(
      fromIndex = startC2ETransferIndex,
      maxNative = if strictC2ETransfersActivated then None else Some(MaxWithdrawals - rewardWithdrawal.size)
    )

    val nativeTransfersViaWithdrawals = transfers.flatMap {
      case x: ContractTransfer.NativeViaWithdrawal => Some(x)
      case _: ContractTransfer.NativeViaDeposit    => None
      case _: ContractTransfer.Asset               => None
    }

    val nativeTransferWithdrawals =
      if strictC2ETransfersActivated
      then Vector.empty
      else toWithdrawals(nativeTransfersViaWithdrawals, rewardWithdrawal.lastOption.fold(startElWithdrawalIndex)(_.index + 1))

    val withdrawals = rewardWithdrawal ++ nativeTransferWithdrawals

    val (addedAssets, updateAssetRegistryTransaction) =
      if (epochInfo.number < chainContractOptions.assetTransfersActivationEpoch) (Nil, None)
      else {
        val startAssetRegistryIndex = lastAssetRegistryIndex + 1
        val assetRegistrySize       = chainContractClient.getAssetRegistrySize
        val addedAssets =
          if (startAssetRegistryIndex == assetRegistrySize) Nil
          else chainContractClient.getRegisteredAssets(startAssetRegistryIndex until assetRegistrySize)

        val txn =
          if (addedAssets.isEmpty) None
          else
            chainContractOptions.elStandardBridgeAddress.map { sba =>
              StandardBridge.mkUpdateAssetRegistryTransaction(
                standardBridgeAddress = sba,
                addedTokenExponents = addedAssets.map(_.exponent),
                addedTokens = addedAssets.map(_.erc20Address)
              )
            }

        (addedAssets, txn)
      }

    val nativeAndAssetTransfersViaDeposits = transfers.flatMap {
      case _: ContractTransfer.NativeViaWithdrawal                         => None
      case x: (ContractTransfer.NativeViaDeposit | ContractTransfer.Asset) => Some(x)
    }

    val depositedTransactions = updateAssetRegistryTransaction.toVector ++
      (for {
        sba      <- chainContractOptions.elStandardBridgeAddress.toVector
        transfer <- nativeAndAssetTransfersViaDeposits
      } yield {
        transfer match {
          case x: ContractTransfer.NativeViaDeposit =>
            StandardBridge.mkFinalizeBridgeETHTransaction(
              transferIndex = x.index,
              standardBridgeAddress = sba,
              from = x.from,
              to = x.to,
              amount = x.amount
            )
          case x: ContractTransfer.Asset =>
            StandardBridge.mkFinalizeBridgeErc20Transaction(
              transferIndex = x.index,
              standardBridgeAddress = sba,
              token = x.tokenAddress,
              to = x.to,
              from = x.from,
              amount = x.amount
            )
        }
      })

    val prevRandao = calculateRandao(epochInfo.hitSource, parentBlock.hash)

    val (nativeTransfersViaDeposits, assetTransfers) = nativeAndAssetTransfersViaDeposits.partitionMap {
      case x: ContractTransfer.NativeViaDeposit => Left(x)
      case x: ContractTransfer.Asset            => Right(x)
    }

    if (willSimulateBlock) {
      engineApiClient
        .mkSimulatedBlock(parentBlock.hash, epochInfo.rewardAddress, nextBlockUnixTs, prevRandao, withdrawals, depositedTransactions)
        .map { simulatedPayload =>
          logger.info(
            s"Starting to simulate payload by miner ${epochInfo.miner} at height ${parentBlock.height + 1} " +
              s"of epoch ${epochInfo.number} (ref=${parentBlock.hash})" +
              (if (withdrawals.isEmpty) "" else s", ${withdrawals.size} withdrawals from EL index=$startElWithdrawalIndex") +
              (if (transfers.isEmpty) "" else s", total ${transfers.size} transfers from $startC2ETransferIndex") +
              (if (nativeTransfersViaWithdrawals.isEmpty) "" else s", ${nativeTransfersViaWithdrawals.size} native via withdrawals") +
              (if (nativeTransfersViaDeposits.isEmpty) "" else s", ${nativeTransfersViaDeposits.size} native via deposits") +
              (if (assetTransfers.isEmpty) "" else s", ${assetTransfers.size} asset transfers") +
              updateAssetRegistryTransaction.fold("")(_ => s", ${addedAssets.size} new assets: {${addedAssets.mkString(", ")}}")
          )

          MiningData(
            payload = simulatedPayload ++ Json.obj("transactions" -> depositedTransactions.map(_.toHex)),
            nextBlockUnixTs = nextBlockUnixTs,
            lastC2ETransferIndex = transfers.lastOption.fold(lastC2ETransferIndex)(_.index),
            lastElWithdrawalIndex = lastElWithdrawalIndex + withdrawals.size,
            lastAssetRegistryIndex = addedAssets.lastOption.fold(lastAssetRegistryIndex)(_.index)
          )
        }
    } else {
      engineApiClient
        .forkchoiceUpdatedWithPayload(
          parentBlock.hash,
          if (finalizedBlock.height > parentBlock.height) parentBlock.hash else finalizedBlock.hash,
          nextBlockUnixTs,
          epochInfo.rewardAddress,
          prevRandao,
          withdrawals,
          depositedTransactions.map(_.toHex)
        )
        .map { payloadId =>
          logger.info(
            s"Starting to forge payload $payloadId by miner ${epochInfo.miner} at height ${parentBlock.height + 1} " +
              s"of epoch ${epochInfo.number} (ref=${parentBlock.hash})" +
              (if (withdrawals.isEmpty) "" else s", ${withdrawals.size} withdrawals from EL index=$startElWithdrawalIndex") +
              (if (transfers.isEmpty) "" else s", total ${transfers.size} transfers from $startC2ETransferIndex") +
              (if (nativeTransfersViaWithdrawals.isEmpty) "" else s", ${nativeTransfersViaWithdrawals.size} native via withdrawals") +
              (if (nativeTransfersViaDeposits.isEmpty) "" else s", ${nativeTransfersViaDeposits.size} native via deposits") +
              (if (assetTransfers.isEmpty) "" else s", ${assetTransfers.size} asset transfers") +
              updateAssetRegistryTransaction.fold("")(_ => s", ${addedAssets.size} new assets: {${addedAssets.mkString(", ")}}")
          )

          MiningData(
            payload = payloadId,
            nextBlockUnixTs = nextBlockUnixTs,
            lastC2ETransferIndex = transfers.lastOption.fold(lastC2ETransferIndex)(_.index),
            lastElWithdrawalIndex = lastElWithdrawalIndex + withdrawals.size,
            lastAssetRegistryIndex = addedAssets.lastOption.fold(lastAssetRegistryIndex)(_.index)
          )
        }
    }
  }

  private def tryToStartMining(prevState: Working[ChainStatus], nodeChainInfo: Either[ChainSwitchInfo, ChainInfo]): Unit = {
    val parentBlock = prevState.lastEcBlock
    val epochInfo   = prevState.epochInfo

    val chosenKeyPair =
      if (config.privateKeys.nonEmpty) config.privateKeys.map(PKKeyPair(_)).find(_.toAddress == epochInfo.miner)
      else wallet.privateKeyAccount(epochInfo.miner).toOption

    chosenKeyPair match {
      case Some(keyPair) if config.miningEnable =>
        logger.trace(s"Designated miner in epoch ${epochInfo.number} is ${epochInfo.miner}, attempting to build payload")
        val refContractBlock = nodeChainInfo match {
          case Left(chainSwitchInfo) => chainSwitchInfo.referenceBlock
          case Right(chainInfo)      => chainInfo.lastBlock
        }
        val lastC2ETransferIndex = refContractBlock.lastC2ETransferIndex

        (for {
          elWithdrawalIndexBefore <- parentBlock.withdrawals.lastOption.map(_.index) match {
            case Some(r) => Right(r)
            case None =>
              if (parentBlock.height - 1 <= EthereumConstants.GenesisBlockHeight) Right(-1L)
              else getLastWithdrawalIndex(parentBlock.parentHash)
          }
          lastEcBlock <- engineApiClient.getLastExecutionBlock()
          willSimulateBlock = lastEcBlock.hash != parentBlock.hash
          currentUnixTs     = time.correctedTime() / 1000
          nextBlockUnixTs = (parentBlock.timestamp + config.blockDelay.toSeconds).max(
            currentUnixTs +
              // We don't collect transactions for simulated payload, thus we don't need to wait for firstBlockMinDelay
              (if (willSimulateBlock) 0 else config.firstBlockMinDelay.toSeconds)
          )
          miningData <- updateHeadAndStartBuildingPayload(
            epochInfo,
            parentBlock,
            prevState.finalizedBlock,
            nextBlockUnixTs,
            lastC2ETransferIndex,
            elWithdrawalIndexBefore,
            prevState.lastContractBlock.lastAssetRegistryIndex,
            prevState.options,
            Option.unless(parentBlock.height == EthereumConstants.GenesisBlockHeight)(parentBlock.minerRewardL2Address),
            willSimulateBlock
          )
        } yield {
          val newState = prevState.copy(
            epochInfo = epochInfo,
            lastEcBlock = parentBlock,
            chainStatus = Mining(
              keyPair,
              miningData.payload,
              nodeChainInfo,
              miningData.lastC2ETransferIndex,
              miningData.lastElWithdrawalIndex,
              miningData.lastAssetRegistryIndex
            )
          )

          setState("tryToStartMining", newState)
          scheduler.scheduleOnceLabeled("tryToForgeNextBlock", (miningData.nextBlockUnixTs - currentUnixTs).seconds)(
            tryToForgeNextBlock(
              miningData.payload,
              parentBlock.hash,
              miningData.nextBlockUnixTs,
              newState.options.startEpochChainFunction(epochInfo.number, parentBlock.hash, epochInfo.hitSource, nodeChainInfo.toOption),
              newState.options
            )
          )
        }).fold(
          err => logger.error(s"Error starting payload build process for first block: ${err.message}"),
          _ => ()
        )
      case _ =>
        logger.trace(s"Designated miner in epoch ${epochInfo.number} is ${epochInfo.miner}")
    }
  }

  private def tryToForgeNextBlock(
      payloadOrId: PayloadId | JsObject,
      referenceHash: BlockHash,
      timestamp: Long,
      contractFunction: ContractFunction,
      chainContractOptions: ChainContractOptions
  ): Unit = {
    def waitForRefApprovalOnCl: Option[FiniteDuration] = {
      val timestampAheadTime = (timestamp - time.correctedTime() / 1000).max(0)
      if (timestampAheadTime > 0) {
        Some(timestampAheadTime.seconds)
      } else if (!chainContractClient.blockExists(referenceHash)) {
        Some(WaitForReferenceConfirmInterval)
      } else None
    }

    state match {
      case origState @ Working(epochInfo = epochInfo, chainStatus = m: Mining)
          if epochInfo.number == blockchain.height && m.currentPayload == payloadOrId =>
        waitForRefApprovalOnCl match {
          case Some(waitingTime) =>
            scheduler.scheduleOnceLabeled("waitForApproval", waitingTime) {
              tryToForgeNextBlock(payloadOrId, referenceHash, timestamp, contractFunction, chainContractOptions)
            }
          case _ =>
            val getAndApplyPayloadResult = for {
              payload <- payloadOrId match {
                case id: String    => engineApiClient.getPayload(id)
                case jso: JsObject => jso.asRight
              }
              latestValidHashOpt <- engineApiClient.newPayload(payload)
              latestValidHash    <- Either.fromOption(latestValidHashOpt, ClientError("Latest valid hash not defined"))
              _ = logger.info(s"Applied payload, block hash is $latestValidHash, timestamp = $timestamp")
              newNetworkBlock <- NetworkL2Block.signed(payload, m.keyPair.privateKey)
            } yield newNetworkBlock

            getAndApplyPayloadResult match {
              case Left(err) => logger.error(s"Failed to forge block at epoch ${epochInfo.number}: ${err.message}")
              case Right(networkBlock) =>
                val ecBlock         = networkBlock.toEcBlock
                val nextBlockUnixTs = (ecBlock.timestamp + config.blockDelay.toSeconds).max(time.correctedTime() / 1000)
                val nextMiningDataE = updateHeadAndStartBuildingPayload(
                  epochInfo,
                  ecBlock,
                  origState.finalizedBlock,
                  nextBlockUnixTs,
                  m.lastC2ETransferIndex,
                  m.lastElWithdrawalIndex,
                  m.lastAssetRegistryIndex,
                  chainContractOptions,
                  None,
                  false
                )

                nextMiningDataE match {
                  case Left(err) => logger.error(s"Can't change the head to ${ecBlock.hash} and start building the next payload: ${err.message}")
                  case Right(nextMiningData) =>
                    setState("appliedPayload", origState.copy(lastEcBlock = ecBlock))
                    val validateResult = for {
                      ecBlockLogs <- engineApiClient.getLogs(
                        hash = ecBlock.hash,
                        addresses = chainContractOptions.bridgeAddresses(epochInfo.number)
                      )
                      transfersRootHash <- BridgeMerkleTree.getE2CTransfersRootHash(ecBlockLogs)
                      // A forged block can be invalid for some reason. In this case we won't send it and its confirmation transaction to the network.
                      expectedContractBlock = ContractBlock(
                        hash = ecBlock.hash,
                        parentHash = ecBlock.parentHash,
                        epoch = epochInfo.number,
                        height = ecBlock.height,
                        minerRewardL2Address = epochInfo.rewardAddress,
                        chainId = m.nodeChainInfo.fold(_.prevChainId + 1, _.id),
                        e2cTransfersRootHash = transfersRootHash,
                        lastC2ETransferIndex = m.lastC2ETransferIndex,
                        lastAssetRegistryIndex = m.lastAssetRegistryIndex
                      )
                      _ = logger.debug(s"Trying to do a full validation of a forged block ${ecBlock.hash}")
                      _ <- validateAppliedBlock(expectedContractBlock, ecBlock, origState, Some(ecBlockLogs), updateState = false)
                    } yield (transfersRootHash, expectedContractBlock)

                    validateResult match {
                      case Left(err) => logger.error(s"Forged an invalid block ${ecBlock.hash}: ${err.message}")
                      case Right((transfersRootHash, expectedContractBlock)) =>
                        val confirmElBlockOnCl = for {
                          funcCall <- contractFunction.toFunctionCall(
                            ecBlock.hash,
                            transfersRootHash,
                            m.lastC2ETransferIndex,
                            m.lastAssetRegistryIndex
                          )
                          _ <- callContract(funcCall, m.keyPair)
                        } yield ()

                        confirmElBlockOnCl match {
                          case Left(err) =>
                            val message = s"Can't confirm block ${ecBlock.hash} on CL: ${err.message}"
                            if (err.message.contains("not allowed to forge blocks in this epoch"))
                              logger.debug(message) // Expected in the end of epoch
                            else logger.error(message)

                          case _ =>
                            // We update state here, because the full validation will fail on other nodes, if we haven't a confirmation transaction
                            setState(
                              "tryToForgeNextBlock",
                              origState.copy(
                                lastEcBlock = ecBlock,
                                chainStatus = m.copy(
                                  currentPayload = nextMiningData.payload,
                                  lastC2ETransferIndex = nextMiningData.lastC2ETransferIndex,
                                  lastElWithdrawalIndex = nextMiningData.lastElWithdrawalIndex,
                                  lastAssetRegistryIndex = nextMiningData.lastAssetRegistryIndex
                                ),
                                fullValidationStatus = FullValidationStatus(
                                  lastValidatedBlock = expectedContractBlock,
                                  lastElWithdrawalIndex = Some(m.lastElWithdrawalIndex)
                                )
                              )
                            )

                            Try(allChannels.broadcast(networkBlock)).recover { err =>
                              logger.warn(s"Failed to broadcast block ${networkBlock.hash}: ${err.toString}")
                            }

                            // TODO: See a comment about prepareAndApplyPayload call above
                            scheduler.scheduleOnceLabeled("forgeSecond", (nextBlockUnixTs - time.correctedTime() / 1000).min(1).seconds)(
                              tryToForgeNextBlock(
                                payloadOrId = nextMiningData.payload,
                                referenceHash = ecBlock.hash,
                                timestamp = nextBlockUnixTs,
                                contractFunction = chainContractOptions.appendFunction(epochInfo.number, ecBlock.hash),
                                chainContractOptions = chainContractOptions
                              )
                            )
                        }
                    }
                }
            }
        }

      case Working(chainStatus = _: Mining | _: FollowingChain) =>
      // a new epoch started, and we're trying to apply a previous epoch payload:
      // Mining - we mine again
      // FollowingChain - we validate
      case other => logger.debug(s"Unexpected state $other attempting to finish building payload")
    }
  }

  private def updateStartingState(): Unit = {
    if (!chainContractClient.isContractSetup) logger.debug("Waiting for chain contract setup")
    else if (chainContractClient.getAllActualMiners.isEmpty) logger.debug("Waiting for at least one miner to join")
    else {
      val finalizedBlock = chainContractClient.getFinalizedBlock
      logger.debug(s"Finalized block is ${finalizedBlock.hash}")
      engineApiClient.getBlockByHash(finalizedBlock.hash) match {
        case Left(error) => logger.error(s"Could not load finalized block", error)
        case Right(Some(finalizedEcBlock)) =>
          logger.trace(s"Finalized block ${finalizedBlock.hash} is at height ${finalizedEcBlock.height}")
          (for {
            newEpochInfo  <- chainContractClient.calculateEpochInfo(blockchain)
            mainChainInfo <- chainContractClient.getMainChainInfo.toRight("Can't get main chain info")
            lastEcBlock   <- engineApiClient.getLastExecutionBlock().leftMap(_.message)
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
          setState("updateStartingState", WaitingForSyncHead(finalizedBlock, requestAndProcessBlock(finalizedBlock.hash)))
      }
    }
  }

  private def handleConsensusLayerChanged(): Unit =
    (for {
      _ <- Either.raiseWhen(chainContractClient.isStopped)(s"Chain $contractAddress is stopped")
      _ <- registryAddress.toLeft(()).leftFlatMap { addr =>
        Either.raiseUnless(blockchain.accountData(addr, registryKey(contractAddress)).contains(BooleanDataEntry(registryKey(contractAddress), true)))(
          s"Chain ${contractAddress} is not enabled in the registry $addr"
        )
      }
    } yield ()) match {
      case Left(message) =>
        logger.warn(message)
        setState("stopMining", Starting)
      case Right(_) =>
        state match {
          case Starting      => updateStartingState()
          case w: Working[?] => updateWorkingState(w)
          case other         => logger.debug(s"Unexpected state: $other")
        }
    }

  private def requestBlocksAndStartMining(prevState: Working[FollowingChain]): Unit = {
    def check(missedBlock: ContractBlock): Unit = {
      state match {
        case w @ Working(epochInfo, lastEcBlock, finalizedBlock, mainChainInfo, _, fc: FollowingChain, _, returnToMainChainInfo)
            if fc.nextExpectedBlock.map(_.hash).contains(missedBlock.hash) && canSupportAnotherAltChain(fc.nodeChainInfo) =>
          logger.debug(s"Block ${missedBlock.hash} wasn't received for $WaitRequestedBlockTimeout, need to switch to alternative chain")
          (for {
            lastValidBlock <- getAltChainReferenceBlock(fc.nodeChainInfo, missedBlock)
            updatedState   <- rollbackDryRun(w, lastValidBlock, finalizedBlock)
          } yield {
            val updatedReturnToMainChainInfo =
              if (fc.nodeChainInfo.isMain) {
                Some(ReturnToMainChainInfo(missedBlock, lastEcBlock, mainChainInfo.id))
              } else returnToMainChainInfo

            chainContractClient.findAltChain(fc.nodeChainInfo.id, lastValidBlock.hash) match {
              case Some(altChainInfo) =>
                engineApiClient.getBlockByHash(finalizedBlock.hash) match {
                  case Right(Some(finalizedEcBlock)) =>
                    followChainAndStartMining(
                      updatedState.copy(chainStatus = FollowingChain(altChainInfo, None), returnToMainChainInfo = updatedReturnToMainChainInfo),
                      epochInfo,
                      altChainInfo.id,
                      finalizedEcBlock,
                      finalizedBlock,
                      chainContractClient.getOptions
                    )
                  case Right(None) =>
                    logger.warn(s"Finalized block ${finalizedBlock.hash} is not in EC")
                  case Left(err) =>
                    logger.error(s"Could not load finalized block ${finalizedBlock.hash}", err)
                }
              case _ =>
                val chainSwitchInfo = ChainSwitchInfo(fc.nodeChainInfo.id, lastValidBlock)

                val newState =
                  updatedState.copy(chainStatus = WaitForNewChain(chainSwitchInfo), returnToMainChainInfo = updatedReturnToMainChainInfo)
                setState("requestBlocksAndStartMining", newState)
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
              scheduler.scheduleOnceLabeled("nextCheck", WaitRequestedBlockTimeout) {
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
        scheduler.scheduleOnceLabeled("firstCheck", WaitRequestedBlockTimeout) {
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

  private def updateMiningState(prevState: Working[Mining], finalizedBlock: ContractBlock, options: ChainContractOptions): Unit = {
    chainContractClient.getMainChainInfo match {
      case Some(mainChainInfo) =>
        val newChainInfo = prevState.chainStatus.nodeChainInfo match {
          case Left(chainSwitchInfo) =>
            chainContractClient.findAltChain(chainSwitchInfo.prevChainId, chainSwitchInfo.referenceBlock.hash)
          case Right(chainInfo) => chainContractClient.getChainInfo(chainInfo.id)
        }

        setState(
          "updateMiningState",
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
        setState("updateMiningState, error", Starting)
    }
  }

  private def updateWorkingState(prevState: Working[ChainStatus]): Unit = {
    val finalizedBlock = chainContractClient.getFinalizedBlock
    val options        = chainContractClient.getOptions
    logger.trace(s"Finalized block is ${finalizedBlock.hash}")
    engineApiClient.getBlockByHash(finalizedBlock.hash) match {
      case Left(error) => logger.error(s"Could not load finalized block: ${error.message}")
      case Right(Some(finalizedEcBlock)) =>
        logger.trace(s"Finalized block ${finalizedBlock.hash} is at height ${finalizedEcBlock.height}")

        lazy val newEpochInfo = chainContractClient.calculateEpochInfo(blockchain)
        // When idle miner was evicted, a list of miners has changed,
        // and we would like it to start mining right away.
        // We use this condition instead of keeping track of a list of miners.
        lazy val errorOrMinerChanged = newEpochInfo.forall(_.miner != prevState.epochInfo.miner)

        if (
          blockchain.height != prevState.epochInfo.number
          || !blockchain.vrf(blockchain.height).contains(prevState.epochInfo.hitSource)
          || errorOrMinerChanged
        ) {
          newEpochInfo match {
            case Left(error) =>
              logger.error(s"Could not calculate epoch info at epoch start: $error")
              setState("updateWorkingState, error", Starting)
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
                  chainContractClient.findAltChain(chainSwitchInfo.prevChainId, chainSwitchInfo.referenceBlock.hash) match {
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
                      setState("updateWorkingState, wait alt chain", newState)
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
            case m: Mining => updateMiningState(prevState.copy(chainStatus = m), finalizedBlock, options)
            case WaitForNewChain(chainSwitchInfo) =>
              val newChainInfo = chainContractClient.findAltChain(chainSwitchInfo.prevChainId, chainSwitchInfo.referenceBlock.hash)
              newChainInfo.foreach { chainInfo =>
                updateToFollowChain(prevState, prevState.epochInfo, chainInfo.id, finalizedEcBlock, finalizedBlock, options)
              }
          }
        }
        validateAppliedBlocks()
        requestMainChainBlock()
      case Right(None) =>
        logger.trace(s"Finalized block ${finalizedBlock.hash} is not in EC, requesting from peers")
        setState("updateWorkingState, finalized", WaitingForSyncHead(finalizedBlock, requestAndProcessBlock(finalizedBlock.hash)))
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
    setState("followChainAndRequestNextBlock", newState)
    maybeRequestNextBlock(newState, finalizedBlock)
  }

  private def requestBlock(contractBlock: ContractBlock): BlockRequestResult = {
    logger.trace(s"Requesting block ${contractBlock.hash}")
    engineApiClient.getBlockByHash(contractBlock.hash) match {
      case Right(Some(block)) => BlockRequestResult.BlockExists(block)
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
              case BlockRequestResult.BlockExists(block) =>
                logger.debug(s"Block ${returnToMainChainInfo.missedBlock.hash} exists at execution chain, trying to validate")
                validateAppliedBlock(returnToMainChainInfo.missedBlock, block, w) match {
                  case Right(updatedState) =>
                    logger.debug(s"Missed block ${block.hash} of main chain ${returnToMainChainInfo.chainId} was successfully validated")
                    chainContractClient.getChainInfo(returnToMainChainInfo.chainId) match {
                      case Some(mainChainInfo) =>
                        confirmBlockAndFollowChain(block, updatedState, mainChainInfo, None)
                      case _ =>
                        logger.error(s"Failed to get chain ${returnToMainChainInfo.chainId} info: not found")
                    }
                  case Left(err) =>
                    logger.debug(s"Missed block ${block.hash} of main chain ${returnToMainChainInfo.chainId} validation error: ${err.message}")
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
      case Failure(exception)   => logger.error(s"Error requesting block $hash from peers", exception)
    }(using globalScheduler)
  }

  private def updateToFollowChain(
      prevState: Working[ChainStatus],
      epochInfo: EpochInfo,
      prevChainId: Long,
      finalizedEcBlock: EcBlock,
      finalizedContractBlock: ContractBlock,
      options: ChainContractOptions
  ): Option[Working[FollowingChain]] = {
    @tailrec
    def findLastEcBlock(curBlock: ContractBlock): EcBlock = {
      engineApiClient.getBlockByHash(curBlock.hash) match {
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

    def followChain(
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
      setState("followChain", newState)
      maybeRequestNextBlock(newState, finalizedContractBlock)
    }

    def rollbackAndFollowChain(
        target: L2BlockLike,
        nodeChainInfo: ChainInfo,
        mainChainInfo: ChainInfo,
        returnToMainChainInfo: Option[ReturnToMainChainInfo]
    ): Option[Working[FollowingChain]] = {
      rollbackDryRun(prevState, target, finalizedContractBlock) match {
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
          Some(
            followChain(
              prevChainInfo,
              prevState.lastEcBlock,
              mainChainInfo,
              prevState.fullValidationStatus,
              prevState.returnToMainChainInfo
            )
          )
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

  private def waitForSyncCompletion(target: ContractBlock): Unit = scheduler.scheduleOnceLabeled("sync", 5.seconds)(state match {
    case SyncingToFinalizedBlock(finalizedBlockHash) if finalizedBlockHash == target.hash =>
      logger.trace(s"Checking if EL has synced to ${target.hash} on height ${target.height}")
      engineApiClient.getLastExecutionBlock() match {
        case Left(error) =>
          logger.error(s"Sync to ${target.hash} was not completed, error=${error.message}")
          setState("waitForSyncCompletion", Starting)
        case Right(lastBlock) if lastBlock.hash == target.hash =>
          logger.debug(s"Finished synchronization to ${target.hash} successfully")
          chainContractClient.calculateEpochInfo(blockchain) match {
            case Left(err) =>
              logger.error(s"Could not transition to following chain state: $err")
              setState("waitForSyncCompletion", Starting)
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
                    lastBlock,
                    mainChainInfo,
                    target,
                    fullValidationStatus,
                    chainContractClient.getOptions,
                    None
                  )
                case _ =>
                  logger.error(s"Can't get main chain info")
                  setState("waitForSyncCompletion", Starting)
              }
          }
        case Right(lastBlock) =>
          logger.trace(s"Sync to ${target.hash} is in progress: current last block is ${lastBlock.hash} at height ${lastBlock.height}")
          waitForSyncCompletion(target)
      }
    case other =>
      logger.debug(s"Unexpected state on sync: $other")
  })

  private def validateRandao(block: EcBlock, epochNumber: Int): JobResult[Unit] =
    blockchain.vrf(epochNumber) match {
      case None => ClientError(s"VRF of $epochNumber epoch is empty").asLeft
      case Some(vrf) =>
        val expectedPrevRandao = calculateRandao(vrf, block.parentHash)
        Either.raiseUnless(expectedPrevRandao == block.prevRandao) {
          ClientError(s"expected prevRandao $expectedPrevRandao, got ${block.prevRandao}, VRF=$vrf of $epochNumber")
        }
    }

  // Of a current epoch miner
  private def validateBlockSignature(block: NetworkL2Block, epochInfo: Option[EpochInfo]): JobResult[Unit] = {
    epochInfo match {
      case Some(epochMeta) =>
        for {
          _ <- Either.raiseUnless(block.minerRewardL2Address == epochMeta.rewardAddress) {
            ClientError(s"block miner ${block.minerRewardL2Address} doesn't equal to ${epochMeta.rewardAddress}")
          }
          signature <- Either.fromOption(block.signature, ClientError(s"signature not found"))
          publicKey <- Either.fromOption(
            chainContractClient.getMinerPublicKey(block.minerRewardL2Address),
            ClientError(s"public key for block miner ${block.minerRewardL2Address} not found")
          )
          _ <- Either.raiseUnless(crypto.verify(signature, Json.toBytes(block.payload), publicKey, checkWeakPk = true)) {
            ClientError(s"invalid signature")
          }
        } yield ()
      case _ => Either.unit
    }
  }

  private def validateTimestamp(newNetworkBlock: NetworkL2Block, parentEcBlock: EcBlock): JobResult[Unit] = {
    val minAppendTs = parentEcBlock.timestamp + config.blockDelay.toSeconds
    Either.raiseUnless(newNetworkBlock.timestamp >= minAppendTs) {
      ClientError(
        s"timestamp (${newNetworkBlock.timestamp}) of appended block must be greater or equal $minAppendTs, " +
          s"Δ${minAppendTs - newNetworkBlock.timestamp}s"
      )
    }
  }

  private def preValidateBlock(networkBlock: NetworkL2Block, parentBlock: EcBlock, epochInfo: Option[EpochInfo]): JobResult[Unit] = for {
    _ <- validateTimestamp(networkBlock, parentBlock)
    _ <- validateBlockSignature(networkBlock, epochInfo)
  } yield ()

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
      networkBlock: NetworkL2Block,
      ch: Channel,
      prevState: Working[ChainStatus],
      contractBlock: ContractBlock,
      parentBlock: EcBlock,
      nodeChainInfo: ChainInfo
  ): Unit = {
    validateAndApplyBlockFull(networkBlock, contractBlock, parentBlock, prevState) match {
      case Right(updatedState) =>
        logger.debug(s"Missed block ${networkBlock.hash} of main chain ${nodeChainInfo.id} was successfully validated")
        broadcastAndConfirmBlock(networkBlock, ch, updatedState, nodeChainInfo, None)
      case Left(err) =>
        logger.debug(s"Missed block ${networkBlock.hash} of main chain ${nodeChainInfo.id} validation error: ${err.message}, ignoring block")
    }
  }

  private def validateAndApply(
      networkBlock: NetworkL2Block,
      ch: Channel,
      prevState: Working[ChainStatus],
      parentBlock: EcBlock,
      nodeChainInfo: ChainInfo,
      returnToMainChainInfo: Option[ReturnToMainChainInfo]
  ): Unit = {
    chainContractClient.getBlock(networkBlock.hash) match {
      case Some(contractBlock) if prevState.fullValidationStatus.lastValidatedBlock.hash == parentBlock.hash =>
        // All blocks before current was fully validated, so we can perform full validation of this block.
        validateAndApplyBlockFull(networkBlock, contractBlock, parentBlock, prevState) match {
          case Right(updatedState) =>
            logger.debug(s"Block ${networkBlock.hash} was successfully validated")
            broadcastAndConfirmBlock(networkBlock, ch, updatedState, nodeChainInfo, returnToMainChainInfo)
          case Left(err) =>
            logger.debug(s"Block ${networkBlock.hash} validation error: ${err.message}")
            processInvalidBlock(contractBlock, prevState, Some(nodeChainInfo))
        }
      case contractBlock =>
        // We should check block signature based on epochInfo if block is not at contract yet
        val epochInfo = Option.when(contractBlock.isEmpty)(prevState.epochInfo)
        applyBlock(networkBlock, parentBlock, epochInfo) match {
          case Right(_) =>
            logger.debug(s"Block ${networkBlock.hash} successfully applied")
            broadcastAndConfirmBlock(networkBlock, ch, prevState, nodeChainInfo, returnToMainChainInfo)
          case Left(err) =>
            logger.error(s"Can't apply ${networkBlock.hash}: ${err.message}, ignoring")
        }
    }
  }

  private def confirmBlockAndFollowChain(
      block: EcBlock,
      prevState: Working[ChainStatus],
      nodeChainInfo: ChainInfo,
      returnToMainChainInfo: Option[ReturnToMainChainInfo]
  ): Unit = {
    val finalizedBlock = prevState.finalizedBlock
    confirmBlock(block, finalizedBlock)
      .fold[Unit](
        err => logger.error(s"Can't confirm block ${block.hash} of chain ${nodeChainInfo.id}: ${err.message}"),
        _ => {
          logger.info(s"Successfully confirmed block ${block.hash} of chain ${nodeChainInfo.id}") // TODO confirmed on EL
          followChainAndRequestNextBlock(
            prevState.epochInfo,
            nodeChainInfo,
            block,
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
      networkBlock: NetworkL2Block,
      ch: Channel,
      prevState: Working[ChainStatus],
      nodeChainInfo: ChainInfo,
      returnToMainChainInfo: Option[ReturnToMainChainInfo]
  ): Unit = {
    Try(allChannels.broadcast(networkBlock, Some(ch))).recover { err =>
      logger.error(s"Failed to broadcast block ${networkBlock.hash}: ${err.getMessage}")
    }

    confirmBlockAndFollowChain(networkBlock.toEcBlock, prevState, nodeChainInfo, returnToMainChainInfo)
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
    if (prevState.lastEcBlock.height < prevState.chainStatus.nodeChainInfo.lastBlock.height) {
      logger.debug(s"EC chain is not synced, trying to find next block to request")
      findBlockChild(prevState.lastEcBlock.hash, prevState.chainStatus.nodeChainInfo.lastBlock.hash) match {
        case Left(error) =>
          logger.error(s"Could not find child of ${prevState.lastEcBlock.hash} on contract: $error")
          prevState
        case Right(contractBlock) =>
          requestBlock(contractBlock) match {
            case BlockRequestResult.BlockExists(ecBlock) =>
              logger.trace(s"Block ${contractBlock.hash} exists at EC chain, trying to confirm")
              confirmBlock(ecBlock, finalizedBlock) match {
                case Right(_) =>
                  val newState = prevState.copy(
                    lastEcBlock = ecBlock,
                    chainStatus = FollowingChain(prevState.chainStatus.nodeChainInfo, None)
                  )
                  setState("maybeRequestNextBlock, confirmed", newState)
                  maybeRequestNextBlock(newState, finalizedBlock)
                case Left(err) =>
                  logger.error(s"Failed to confirm next block ${ecBlock.hash}: ${err.message}")
                  prevState
              }
            case BlockRequestResult.Requested(contractBlock) =>
              val newState = prevState.copy(chainStatus = prevState.chainStatus.copy(nextExpectedBlock = Some(contractBlock)))
              setState("maybeRequestNextBlock, requested", newState)
              newState
          }
      }
    } else {
      logger.trace(s"EC chain ${prevState.chainStatus.nodeChainInfo.id} is synced, no need to request blocks")
      prevState
    }
  }

  private def toWithdrawals(transfers: Vector[ContractTransfer.NativeViaWithdrawal], firstElBlockWithdrawalIndex: Long): Vector[Withdrawal] =
    transfers.zipWithIndex.map { case (x, i) =>
      val index = firstElBlockWithdrawalIndex + i
      toWithdrawal(x, index)
    }

  private def toWithdrawal(transfer: ContractTransfer.NativeViaWithdrawal, ecBlockWithdrawalIndex: Long): Withdrawal =
    Withdrawal(ecBlockWithdrawalIndex, transfer.to, NativeBridge.clToGweiNativeTokenAmount(transfer.amount))

  @tailrec
  private def getLastWithdrawalIndex(hash: BlockHash): JobResult[WithdrawalIndex] =
    engineApiClient.getBlockByHash(hash) match {
      case Left(e)     => Left(e)
      case Right(None) => Left(ClientError(s"Can't find $hash block on EC during withdrawal search"))
      case Right(Some(ecBlock)) =>
        ecBlock.withdrawals.lastOption match {
          case Some(lastWithdrawal) => Right(lastWithdrawal.index)
          case None =>
            if (ecBlock.height == 0) Right(-1L)
            else getLastWithdrawalIndex(ecBlock.parentHash)
        }
    }

  private def validateAssetRegistryUpdate(
      ecBlockLogs: List[GetLogsResponseEntry],
      contractBlock: ContractBlock,
      parentContractBlock: ContractBlock,
      chainContractOptions: ChainContractOptions
  ): JobResult[Unit] = {
    val expectedAddedAssets =
      if (
        contractBlock.epoch < chainContractOptions.assetTransfersActivationEpoch ||
        parentContractBlock.lastAssetRegistryIndex == contractBlock.lastAssetRegistryIndex
      ) Nil
      else {
        val startAssetRegistryIndex = parentContractBlock.lastAssetRegistryIndex + 1
        chainContractClient.getRegisteredAssets(startAssetRegistryIndex to contractBlock.lastAssetRegistryIndex)
      }

    val relatedElRawLogs = ecBlockLogs.filter { x =>
      chainContractOptions.elStandardBridgeAddress.contains(x.address) &&
      x.topics.contains(StandardBridge.RegistryUpdated.Topic)
    }
    for {
      _ <- relatedElRawLogs match {
        case Nil =>
          Either.raiseUnless(expectedAddedAssets.isEmpty) {
            ClientError(s"Expected one asset registry event with ${expectedAddedAssets.size} assets, got 0")
          }

        case elRawLog :: Nil =>
          if (expectedAddedAssets.isEmpty) ClientError(s"Expected no asset registry events, got 1: $elRawLog").asLeft
          else
            for {
              elEvent <- StandardBridge.RegistryUpdated.decodeLog(elRawLog.data).leftMap(ClientError(_))
              _ <- Either.cond(
                elEvent.addedTokens.size == expectedAddedAssets.size,
                true,
                ClientError(s"Expected ${expectedAddedAssets.size} added assets in a RegistryUpdated event, got ${elEvent.addedTokens.size}")
              )
              _ <- Either.cond(
                elEvent.addedTokenExponents.size == expectedAddedAssets.size,
                true,
                ClientError(
                  s"Expected ${expectedAddedAssets.size} added exponent assets in a RegistryUpdated event, got ${elEvent.addedTokenExponents.size}"
                )
              )
              _ <- elEvent.addedTokens.lazyZip(elEvent.addedTokenExponents).lazyZip(expectedAddedAssets).zipWithIndex.toList.traverse {
                case ((actual, actualExponent, expected), i) =>
                  for {
                    _ <- Either.raiseUnless(actual == expected.erc20Address) {
                      ClientError(s"Added asset #$i: expected ${expected.erc20Address}, got $actual")
                    }
                    _ <- Either.raiseUnless(actualExponent == expected.exponent) {
                      ClientError(s"Added asset exponent #$i: expected ${expected.exponent}, got $actualExponent")
                    }
                  } yield ()
              }
              _ <- Either.cond(
                elEvent.removedTokens.isEmpty,
                true,
                ClientError(s"Removing assets is not supported, got ${elEvent.removedTokens.size} addresses")
              )
            } yield ()

        case xs => ClientError(s"Expected one asset registry event with ${expectedAddedAssets.size} assets, got ${xs.size}").asLeft
      }
    } yield ()
  }

  private def skipFinalizedBlocksValidation(curState: Working[ChainStatus]) = {
    if (curState.finalizedBlock.height > curState.fullValidationStatus.lastValidatedBlock.height) {
      val newState = curState.copy(fullValidationStatus = FullValidationStatus(curState.finalizedBlock, None))
      setState("skipFinalizedBlocksValidation", newState)
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
                logger.trace(s"Trying to validate applied block ${block.hash}")
                validateAppliedBlock(block.contractBlock, block.ecBlock, curState) match {
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
        logger.trace(s"Skipping validation of applied blocks: $other")
        Either.unit
    }
  }

  private def validateC2E(
      contractBlock: ContractBlock,
      ecBlock: EcBlock,
      ecBlockLogs: List[GetLogsResponseEntry],
      fullValidationStatus: FullValidationStatus,
      miningReward: Option[MiningReward],
      options: ChainContractOptions
  ): JobResult[Option[WithdrawalIndex]] = for {
    blockJsonE <- engineApiClient.getBlockByHashJson(ecBlock.hash, fullTransactionObjects = true)
    blockJson  <- blockJsonE.toRight(ClientError(s"Can't find EC block ${ecBlock.hash} transactions"))

    strictC2ETransfersActivated = contractBlock.epoch >= chainContractClient.getStrictC2ETransfersActivationEpoch

    _ <- (blockJson \ "transactions").asOpt[Seq[JsObject]].getOrElse(Seq.empty).traverse { txJson =>
      DepositedTransaction
        .parseValidDepositedTransaction(txJson)
        .leftMap(ClientError(_))
        .flatMap {
          case None => Right(())
          case Some(tx) =>
            Either.raiseUnless(
              options.elStandardBridgeAddress.isDefined &&
                tx.to == options.elStandardBridgeAddress &&
                (if strictC2ETransfersActivated then true else tx.mint == BigInteger.ZERO && tx.value == BigInteger.ZERO)
            ) {
              ClientError(s"Transaction not allowed, to: ${tx.to}, standard bridge address: ${options.elStandardBridgeAddress}")
            }
        }
    }

    elWithdrawalIndexBefore <- fullValidationStatus.checkedLastElWithdrawalIndex(ecBlock.parentHash) match {
      case Some(r) => Right(r)
      case None =>
        if (ecBlock.height - 1 <= EthereumConstants.GenesisBlockHeight) Right(-1L)
        else getLastWithdrawalIndex(ecBlock.parentHash)
    }

    parentContractBlock = chainContractClient
      .getBlock(contractBlock.parentHash)
      .getOrElse(throw new RuntimeException(s"Can't find a parent block ${contractBlock.parentHash} of block ${contractBlock.hash}"))

    (expectedTransfers, nextTransfer) = {
      val inBlock = contractBlock.lastC2ETransferIndex - parentContractBlock.lastC2ETransferIndex
      val xs = chainContractClient.getTransfers(
        fromIndex = parentContractBlock.lastC2ETransferIndex + 1,
        max = inBlock + 1
      )
      if (xs.isEmpty) (Vector.empty, None)
      else if (xs.size <= inBlock) (xs, None)
      else (xs.init, xs.lastOption)
    }

    expectedNativeTransfersNumber =
      if strictC2ETransfersActivated then 0
      else
        expectedTransfers.count {
          case _: ContractTransfer.NativeViaWithdrawal => true
          case _: ContractTransfer.NativeViaDeposit    => false
          case _: ContractTransfer.Asset               => false
        }

    // Checks for maximum transfers processing
    _ <- nextTransfer match {
      case None => Either.unit
      case Some(nextTransfer) =>
        Either.raiseUnless(nextTransfer.epoch >= contractBlock.epoch || !strictC2ETransfersActivated)(
          ClientError(s"Block should contain a next C2E transfer: $nextTransfer")
        )
    }

    (prevWithdrawalIndex, actualTransferWithdrawals) <- {
      miningReward match {
        case None =>
          if (ecBlock.withdrawals.size == expectedNativeTransfersNumber) (elWithdrawalIndexBefore, ecBlock.withdrawals).asRight
          else ClientError(s"Expected $expectedNativeTransfersNumber withdrawals, got ${ecBlock.withdrawals.size}").asLeft

        case Some(miningReward) =>
          ecBlock.withdrawals match {
            case actualReward +: actualWithdrawalsForTransfers if ecBlock.withdrawals.size == expectedNativeTransfersNumber + 1 =>
              val expectedReward = Withdrawal(elWithdrawalIndexBefore + 1, miningReward.recipient, miningReward.amount)
              validateWithdrawal(actualReward, expectedReward)
                .map(_ => (elWithdrawalIndexBefore + 1, actualWithdrawalsForTransfers))
                .leftMap(e => ClientError(s"Failed a reward withdrawal validation. $e"))
            case _ =>
              ClientError(s"Expected ${expectedNativeTransfersNumber + 1} (at least reward) withdrawals, got ${ecBlock.withdrawals.size}").asLeft
          }
      }
    }

    lastElWithdrawalIndex <- {
      val c2eLogs = ecBlockLogs.filter(_.topics.intersect(C2ETopics).nonEmpty)
      validateC2ETransfers(actualTransferWithdrawals, c2eLogs, expectedTransfers, prevWithdrawalIndex, strictC2ETransfersActivated).leftMap(
        ClientError.apply
      )
    }
  } yield Some(lastElWithdrawalIndex)

  private def validateC2ETransfers(
      actualWithdrawals: Seq[Withdrawal],
      actualTransferLogs: List[GetLogsResponseEntry],
      expectedTransfers: Seq[ContractTransfer],
      prevWithdrawalIndex: Long,
      strictC2ETransfersActivated: Boolean
  ): Either[String, Long] = {
    val totalTransfers = expectedTransfers.size

    @tailrec
    def loop(
        actualWithdrawals: Seq[Withdrawal],
        actualTransferLogs: List[GetLogsResponseEntry],
        expectedTransfers: Seq[ContractTransfer],
        prevWithdrawalIndex: Long,
        currTransferNumber: Int
    ): Either[String, Long] = {
      def logPrefix = s"[$currTransferNumber/$totalTransfers]"
      expectedTransfers match {
        case Seq() =>
          for {
            _ <- Either.raiseUnless(actualWithdrawals.isEmpty) {
              s"$logPrefix Found ${actualWithdrawals.size} unexpected withdrawals: ${actualWithdrawals.take(MaxTransfersInLogs).mkString(", ")}"
            }
            _ <- Either.raiseUnless(actualTransferLogs.isEmpty) {
              s"$logPrefix Found ${actualTransferLogs.size} unexpected transfers: ${actualTransferLogs.take(MaxTransfersInLogs).mkString(", ")}"
            }
          } yield prevWithdrawalIndex

        case expectedTransfer +: restExpectedTransfers =>
          expectedTransfer match {
            case expectedTransfer: ContractTransfer.NativeViaWithdrawal =>
              if strictC2ETransfersActivated then Left("Native transfers via withdrawals are unexpected after strict C2E transfers activation")
              else
                actualWithdrawals match {
                  case Seq() => s"$logPrefix Not found EL block withdrawal #$prevWithdrawalIndex, expected $expectedTransfer transfer".asLeft
                  case actualWithdrawal +: restActualWithdrawals =>
                    val expectedWithdrawal = toWithdrawal(expectedTransfer, prevWithdrawalIndex + 1)
                    validateWithdrawal(actualWithdrawal, expectedWithdrawal) match {
                      case Left(e) => e.asLeft
                      case _ =>
                        loop(restActualWithdrawals, actualTransferLogs, restExpectedTransfers, expectedWithdrawal.index, currTransferNumber + 1)
                    }
                }
            case expectedTransfer: ContractTransfer.NativeViaDeposit =>
              if strictC2ETransfersActivated then {
                actualTransferLogs match {
                  case Nil => s"$logPrefix Not found EL transfer log, expected $expectedTransfer transfer".asLeft
                  case actualTransferLog :: restActualTransferLogs =>
                    StandardBridge.ETHBridgeFinalized
                      .decodeLog(actualTransferLog)
                      .flatMap(validateC2ENativeTransfer(actualTransferLog.logIndex, _, expectedTransfer)) match {
                      case Left(e) => e.asLeft
                      case _ => loop(actualWithdrawals, restActualTransferLogs, restExpectedTransfers, prevWithdrawalIndex, currTransferNumber + 1)
                    }
                }
              } else Left("Native transfers via deposits are unexpected before strict C2E transfers activation")
            case expectedTransfer: ContractTransfer.Asset =>
              actualTransferLogs match {
                case Nil => s"$logPrefix Not found EL transfer log, expected $expectedTransfer transfer".asLeft
                case actualTransferLog :: restActualTransferLogs =>
                  StandardBridge.ERC20BridgeFinalized
                    .decodeLog(actualTransferLog)
                    .flatMap(validateC2EAssetTransfer(actualTransferLog.logIndex, _, expectedTransfer, strictC2ETransfersActivated)) match {
                    case Left(e) => e.asLeft
                    case _ => loop(actualWithdrawals, restActualTransferLogs, restExpectedTransfers, prevWithdrawalIndex, currTransferNumber + 1)
                  }
              }
          }
      }
    }

    loop(actualWithdrawals, actualTransferLogs, expectedTransfers, prevWithdrawalIndex, currTransferNumber = 1)
  }

  private def validateAndApplyBlockFull(
      networkBlock: NetworkL2Block,
      contractBlock: ContractBlock,
      parentBlock: EcBlock,
      prevState: Working[ChainStatus]
  ): JobResult[Working[ChainStatus]] = {
    logger.trace(s"Trying to apply and do a full validation of block ${networkBlock.hash}")
    for {
      _ <- applyBlock(networkBlock, parentBlock, epochInfo = None) // epochInfo is empty, because we don't need to validate a block signature
      updatedState <- validateAppliedBlock(contractBlock, networkBlock.toEcBlock, prevState)
    } yield updatedState
  }

  private def applyBlock(networkBlock: NetworkL2Block, parentBlock: EcBlock, epochInfo: Option[EpochInfo]): JobResult[Unit] = for {
    _ <- preValidateBlock(networkBlock, parentBlock, epochInfo)
    _ <- engineApiClient.newPayload(networkBlock.payload)
  } yield ()

  private def validateAppliedBlock(
      contractBlock: ContractBlock,
      ecBlock: EcBlock,
      prevState: Working[ChainStatus],
      ecBlockLogs: Option[List[GetLogsResponseEntry]] = None,
      updateState: Boolean = true
  ): JobResult[Working[ChainStatus]] = {
    val validationResult =
      for {
        _ <- Either.raiseUnless(contractBlock.minerRewardL2Address == ecBlock.minerRewardL2Address) {
          ClientError(s"Miner in EC block ${ecBlock.minerRewardL2Address} should be equal to miner on contract ${contractBlock.minerRewardL2Address}")
        }
        parentContractBlock <- chainContractClient
          .getBlock(contractBlock.parentHash)
          .toRight(ClientError(s"Can't find a parent block ${contractBlock.parentHash} of block ${contractBlock.hash} on chain contract"))
        ecBlockLogs <- ecBlockLogs.map(_.asRight).getOrElse {
          engineApiClient.getLogs(
            hash = ecBlock.hash,
            addresses = prevState.options.bridgeAddresses(contractBlock.epoch)
          )
        }
        _ <- validateE2CTransfers(contractBlock, ecBlockLogs)
        _ <- validateAssetRegistryUpdate(ecBlockLogs, contractBlock, parentContractBlock, prevState.options)
        _ <- validateRandao(ecBlock, contractBlock.epoch)
        miningReward = getMinerRewardAddress(contractBlock, parentContractBlock).map(MiningReward(_, prevState.options.miningReward))
        updatedLastElWithdrawalIndex <- validateC2E(
          contractBlock,
          ecBlock,
          ecBlockLogs,
          prevState.fullValidationStatus,
          miningReward,
          prevState.options
        )
      } yield updatedLastElWithdrawalIndex

    validationResult.map { lastElWithdrawalIndex =>
      val newState = prevState.copy(fullValidationStatus =
        FullValidationStatus(
          lastValidatedBlock = contractBlock,
          lastElWithdrawalIndex = lastElWithdrawalIndex
        )
      )
      if (updateState) setState("validateAppliedBlock", newState)
      newState
    }
  }

  private def getMinerRewardAddress(block: ContractBlock, parent: ContractBlock): Option[EthAddress] =
    Option.unless(block.epoch == parent.epoch || block.referencesGenesis)(parent.minerRewardL2Address)

  private def processInvalidBlock(
      contractBlock: ContractBlock,
      prevState: Working[ChainStatus],
      nodeChainInfo: Option[ChainInfo]
  ): Unit = {
    nodeChainInfo.orElse(chainContractClient.getChainInfo(contractBlock.chainId)) match {
      case Some(chainInfo) if canSupportAnotherAltChain(chainInfo) =>
        (for {
          referenceBlock <- getAltChainReferenceBlock(chainInfo, contractBlock)
          updatedState   <- rollbackDryRun(prevState, referenceBlock, prevState.finalizedBlock)
          lastValidBlock <- chainContractClient
            .getBlock(updatedState.lastEcBlock.hash)
            .toRight(ClientError(s"Block ${updatedState.lastEcBlock.hash} not found at contract"))
        } yield {
          chainContractClient.findAltChain(chainInfo.id, lastValidBlock.hash) match {
            case Some(altChainInfo) =>
              val newState = updatedState.copy(
                chainStatus = FollowingChain(altChainInfo, None),
                returnToMainChainInfo = if (chainInfo.isMain) None else updatedState.returnToMainChainInfo
              )
              setState("processInvalidBlock, found alt chain", newState)
              newState
            case _ =>
              val newState = updatedState.copy(
                chainStatus = WaitForNewChain(ChainSwitchInfo(chainInfo.id, lastValidBlock)),
                returnToMainChainInfo = if (chainInfo.isMain) None else updatedState.returnToMainChainInfo
              )
              setState("processInvalidBlock, wait alt chain", newState)
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
            if (curBlock.height > curState.lastEcBlock.height) {
              loop(parentBlock, acc)
            } else {
              engineApiClient.getBlockByHash(curBlock.hash) match {
                case Right(Some(ecBlock)) =>
                  loop(parentBlock, BlockForValidation(curBlock, ecBlock) :: acc)
                case Right(None) =>
                  Left(ClientError(s"Block ${curBlock.hash} not found on EC client for full validation"))
                case Left(err) =>
                  Left(ClientError(s"Can't get EC block ${curBlock.hash} for full validation: ${err.message}"))
              }
            }
          case _ =>
            Left(ClientError(s"Block ${curBlock.parentHash} not found at contract during full validation"))
        }
      }
    }

    loop(curState.lastContractBlock, List.empty)
  }

  private def confirmBlock(block: L2BlockLike, finalizedBlock: L2BlockLike): JobResult[PayloadStatus] = {
    val finalizedBlockHash = if (finalizedBlock.height > block.height) block.hash else finalizedBlock.hash
    engineApiClient.forkchoiceUpdated(block.hash, finalizedBlockHash)
  }

  private def canSupportAnotherAltChain(nodeChainInfo: ChainInfo): Boolean = {
    val chainSupporters = chainContractClient.getSupporters(nodeChainInfo.id)
    val walletAddresses = wallet.privateKeyAccounts.map(_.toAddress).toSet

    nodeChainInfo.isMain || walletAddresses.intersect(chainSupporters).isEmpty
  }

  private def setState(label: String, newState: State): Unit = {
    logger.trace(s"New state after $label: $newState")
    val chainIdOpt = newState match {
      case Working(chainStatus = cs) =>
        cs match {
          case FollowingChain(nodeChainInfo = ChainInfo(id = id)) => Some(id)
          case Mining(nodeChainInfo = Right(nci))                 => Some(nci.id)
          case _                                                  => None
        }
      case _ => None
    }

    chainIdOpt.foreach(chainId => Kamon.gauge("consensus-client.chain-id").withTag("chain-contract", config.chainContract).update(chainId.toDouble))

    state = newState
  }
}

object ELUpdater {
  private val MaxTimeDrift: Int                       = 1 // second
  val WaitForReferenceConfirmInterval: FiniteDuration = 500.millis
  val ClChangedProcessingDelay: FiniteDuration        = 50.millis
  val MiningRetryInterval: FiniteDuration             = 5.seconds
  val WaitRequestedBlockTimeout: FiniteDuration       = 2.seconds
  private val MaxTransfersInLogs                      = 5 // Cut huge logs

  case class EpochInfo(number: Int, miner: Address, rewardAddress: EthAddress, hitSource: ByteStr, prevEpochLastBlockHash: Option[BlockHash])

  sealed trait State
  object State {
    case object Starting extends State

    case class Working[+CS <: ChainStatus](
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
      override def toString: String =
        s"Working($epochInfo,l=${lastEcBlock.hash},f=${finalizedBlock.hash},$mainChainInfo,$fullValidationStatus,$chainStatus,$options,$returnToMainChainInfo)"
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
          currentPayload: PayloadId | JsObject,
          nodeChainInfo: Either[ChainSwitchInfo, ChainInfo],
          lastC2ETransferIndex: Long,
          lastElWithdrawalIndex: WithdrawalIndex,
          lastAssetRegistryIndex: Int
      ) extends ChainStatus {
        override def lastContractBlock: ContractBlock = nodeChainInfo match {
          case Left(chainSwitchInfo) => chainSwitchInfo.referenceBlock
          case Right(chainInfo)      => chainInfo.lastBlock
        }

        override def toString: String =
          s"Mining(m=${keyPair.toAddress}, p=$currentPayload, $nodeChainInfo, c2e=$lastC2ETransferIndex, lwi=$lastElWithdrawalIndex, lari=$lastAssetRegistryIndex)"
      }

      case class WaitForNewChain(chainSwitchInfo: ChainSwitchInfo) extends ChainStatus {
        override def lastContractBlock: ContractBlock = chainSwitchInfo.referenceBlock
      }
    }

    case class WaitingForSyncHead(target: ContractBlock, task: CancelableFuture[BlockWithChannel]) extends State {
      override def toString: String = s"WaitingForSyncHead($target)"
    }
    case class SyncingToFinalizedBlock(target: BlockHash) extends State
  }

  case class ChainSwitchInfo(prevChainId: Long, referenceBlock: ContractBlock) {
    override def toString: String = s"ChainSwitchInfo(prev=$prevChainId, ${referenceBlock.hash})"
  }

  /** We haven't received an EC-block [[missedBlock]] of a previous epoch when started a mining on a new epoch. We can return to the main chain, if we
    * get a missed EC-block.
    */
  case class ReturnToMainChainInfo(missedBlock: ContractBlock, missedBlockParent: EcBlock, chainId: Long) {
    override def toString: String = s"ReturnToMainChainInfo(m=${missedBlock.hash}, p=${missedBlockParent.hash}, c=$chainId)"
  }

  private enum BlockRequestResult {
    case BlockExists(block: EcBlock)
    case Requested(contractBlock: ContractBlock)
  }

  private case class MiningData(
      payload: PayloadId | JsObject,
      nextBlockUnixTs: Long,
      lastC2ETransferIndex: WithdrawalIndex,
      lastElWithdrawalIndex: WithdrawalIndex,
      lastAssetRegistryIndex: Int
  )

  private case class BlockForValidation(contractBlock: ContractBlock, ecBlock: EcBlock) {
    val hash: BlockHash = contractBlock.hash
  }

  case class FullValidationStatus(lastValidatedBlock: ContractBlock, lastElWithdrawalIndex: Option[WithdrawalIndex]) {
    // If we didn't validate the parent block last time, then the index is outdated
    def checkedLastElWithdrawalIndex(parentBlockHash: BlockHash): Option[WithdrawalIndex] =
      lastElWithdrawalIndex.filter(_ => parentBlockHash == lastValidatedBlock.hash)

    override def toString: String = s"FullValidationStatus(l=${lastValidatedBlock.hash}, wi=$lastElWithdrawalIndex)"
  }

  private case class MiningReward(recipient: EthAddress, amount: Gwei)

  def calculateRandao(hitSource: ByteStr, parentHash: BlockHash): String = {
    val msg = hitSource.arr ++ HexBytesConverter.toBytes(parentHash)
    HexBytesConverter.toHex(crypto.secureHash(msg))
  }

  def registryKey(chainContract: Address): String = s"unit_${chainContract}_approved"

  private def validateE2CTransfers(contractBlock: ContractBlock, ecBlockLogs: List[GetLogsResponseEntry]): JobResult[Unit] =
    for {
      elRootHash <- BridgeMerkleTree.getE2CTransfersRootHash(ecBlockLogs)
      _ <- Either.raiseUnless(java.util.Arrays.equals(contractBlock.e2cTransfersRootHash, elRootHash)) { // elRootHash is the source of true
        ClientError(
          s"EL to CL transfers hash of ${contractBlock.hash} are different: " +
            s"EL=${toHexNoPrefix(elRootHash)}, " +
            s"CL=${toHexNoPrefix(contractBlock.e2cTransfersRootHash)}"
        )
      }
    } yield ()

  private def validateWithdrawal(actual: Withdrawal, expected: Withdrawal): Either[String, Unit] = for {
    _ <- Either.raiseUnless(actual.index == expected.index) {
      s"Withdrawal #${actual.index}: expected index ${expected.index} for $actual"
    }
    _ <- Either.raiseUnless(actual.address == expected.address) {
      s"Withdrawal #${actual.index}: expected address ${expected.address}, got: ${actual.address}"
    }
    _ <- Either.raiseUnless(actual.amount == expected.amount) {
      s"Withdrawal #${actual.index}: expected amount ${expected.amount}, got: ${actual.amount}"
    }
  } yield ()

  private def validateC2ENativeTransfer(
      logIndex: EthNumber,
      elTransferEvent: StandardBridge.ETHBridgeFinalized,
      expectedTransfer: ContractTransfer.NativeViaDeposit
  ): Either[String, Unit] = {
    def errorPrefix = s"C2E native transfer with logIndex=$logIndex, transferIndex=${expectedTransfer.index}"
    for {
      _ <- Either.raiseUnless(elTransferEvent.from == expectedTransfer.from) {
        s"$errorPrefix: got from address: ${elTransferEvent.from}, expected: ${expectedTransfer.from}"
      }
      _ <- Either.raiseUnless(elTransferEvent.to == expectedTransfer.to)(
        s"$errorPrefix: got to address: ${elTransferEvent.to}, expected: ${expectedTransfer.to}"
      )
      expectedAmount = WAmount(expectedTransfer.amount).scale(NativeTokenElDecimals - NativeTokenClDecimals)
      _ <- Either.raiseUnless(elTransferEvent.amount == expectedAmount)(
        s"$errorPrefix: got amount: ${elTransferEvent.amount}, expected ${expectedAmount}"
      )
    } yield ()
  }

  private def validateC2EAssetTransfer(
      logIndex: EthNumber,
      elTransferEvent: StandardBridge.ERC20BridgeFinalized,
      expectedTransfer: ContractTransfer.Asset,
      strictC2ETransfersActivated: Boolean
  ): Either[String, Unit] = {
    def errorPrefix = s"C2E asset transfer with logIndex=$logIndex, transferIndex=${expectedTransfer.index}"
    for {
      _ <- Either.raiseUnless(elTransferEvent.localToken == expectedTransfer.tokenAddress) {
        s"$errorPrefix: got ERC20 address: ${elTransferEvent.localToken}, expected: ${expectedTransfer.tokenAddress}"
      }
      _ <-
        if strictC2ETransfersActivated then
          Either.raiseUnless(elTransferEvent.from == expectedTransfer.from) {
            s"$errorPrefix: got from address: ${elTransferEvent.from}, expected: ${expectedTransfer.from}"
          }
        else Right(())
      _ <- Either.raiseUnless(elTransferEvent.elTo == expectedTransfer.to) {
        s"$errorPrefix: got to address: ${elTransferEvent.elTo}, expected: ${expectedTransfer.to}"
      }
      _ <- Either.raiseUnless(elTransferEvent.amount == expectedTransfer.amount) {
        s"$errorPrefix: got amount: ${elTransferEvent.amount}, expected ${expectedTransfer.amount}"
      }
    } yield ()
  }
}
