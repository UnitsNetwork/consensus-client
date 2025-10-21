package units.client.engine

import play.api.libs.json.*
import units.client.JsonRpcClient.newRequestId
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.client.engine.model.Withdrawal.WithdrawalIndex
import units.el.DepositedTransaction
import units.eth.{EmptyL2Block, EthAddress}
import units.util.BlockToPayloadMapper
import units.{BlockHash, Result}

import scala.annotation.tailrec

trait EngineApiClient {
  def forkchoiceUpdated(blockHash: BlockHash, finalizedBlockHash: BlockHash, requestId: Int = newRequestId): Result[PayloadStatus]

  def forkchoiceUpdatedWithPayload(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal] = Vector.empty,
      transactions: Vector[String] = Vector.empty,
      requestId: Int = newRequestId
  ): Result[PayloadId]

  def getPayload(payloadId: PayloadId, requestId: Int = newRequestId): Result[JsObject]

  def newPayload(payload: JsObject, requestId: Int = newRequestId): Result[Option[BlockHash]]

  def getPayloadBodyByHash(hash: BlockHash, requestId: Int = newRequestId): Result[Option[JsObject]]

  def getBlockByNumber(number: BlockNumber, requestId: Int = newRequestId): Result[Option[EcBlock]]

  def getBlockByHash(hash: BlockHash, requestId: Int = newRequestId): Result[Option[EcBlock]]

  def simulate(blockStateCalls: Seq[BlockStateCall], hash: BlockHash, requestId: Int = newRequestId): Result[Seq[JsObject]]

  def getBlockByHashJson(hash: BlockHash, fullTransactionObjects: Boolean = false, requestId: Int = newRequestId): Result[Option[JsObject]]

  def getLastExecutionBlock(requestId: Int = newRequestId): Result[EcBlock]

  def blockExists(hash: BlockHash, requestId: Int = newRequestId): Result[Boolean]

  def getLogs(
      hash: BlockHash,
      addresses: List[EthAddress] = Nil,
      topics: List[String] = Nil,
      requestId: Int = newRequestId
  ): Result[List[GetLogsResponseEntry]]

  def onRetry(requestId: Int): Unit = {}
}

object EngineApiClient {
  type PayloadId = String

  extension (c: EngineApiClient) {
    def mkSimulatedBlock(
        rollbackTargetBlockId: BlockHash,
        feeRecipient: EthAddress,
        time: Long,
        prevRandao: String,
        withdrawals: Seq[Withdrawal],
        depositedTransactions: Seq[DepositedTransaction]
    ): Result[JsObject] = for {
      targetBlockOpt <- c.getBlockByHash(rollbackTargetBlockId)
      targetBlock    <- targetBlockOpt.toRight(s"Target block $rollbackTargetBlockId is not in EC")
      simulatedBlockJson <- c.simulate(
        EmptyL2Block.mkSimulateCall(targetBlock, feeRecipient, time, prevRandao, withdrawals, depositedTransactions),
        targetBlock.hash
      )
    } yield BlockToPayloadMapper.toPayloadJson(simulatedBlockJson.head, Json.obj("transactions" -> Json.arr(), "withdrawals" -> withdrawals))

    @tailrec
    def getLastWithdrawalIndex(hash: BlockHash): Result[WithdrawalIndex] =
      c.getBlockByHash(hash) match {
        case Left(e)     => Left(e)
        case Right(None) => Left(s"Can't find $hash block on EC during withdrawal search")
        case Right(Some(ecBlock)) =>
          ecBlock.withdrawals.lastOption match {
            case Some(lastWithdrawal) => Right(lastWithdrawal.index)
            case None =>
              if (ecBlock.height == 0) Right(-1L)
              else getLastWithdrawalIndex(ecBlock.parentHash)
          }
      }
  }
}
