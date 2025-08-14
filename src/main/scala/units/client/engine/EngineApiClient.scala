package units.client.engine

import play.api.libs.json.*
import units.client.JsonRpcClient.newRequestId
import units.client.engine.EngineApiClient.PayloadId
import units.client.engine.model.*
import units.el.DepositedTransaction
import units.eth.{EmptyL2Block, EthAddress}
import units.util.BlockToPayloadMapper
import units.{BlockHash, ClientError, JobResult}

trait EngineApiClient {
  def forkchoiceUpdated(blockHash: BlockHash, finalizedBlockHash: BlockHash, requestId: Int = newRequestId): JobResult[PayloadStatus]

  def forkchoiceUpdatedWithPayload(
      lastBlockHash: BlockHash,
      finalizedBlockHash: BlockHash,
      unixEpochSeconds: Long,
      suggestedFeeRecipient: EthAddress,
      prevRandao: String,
      withdrawals: Vector[Withdrawal] = Vector.empty,
      transactions: Vector[String] = Vector.empty,
      requestId: Int = newRequestId
  ): JobResult[PayloadId]

  def getPayload(payloadId: PayloadId, requestId: Int = newRequestId): JobResult[JsObject]

  def newPayload(payload: JsObject, requestId: Int = newRequestId): JobResult[Option[BlockHash]]

  def getPayloadBodyByHash(hash: BlockHash, requestId: Int = newRequestId): JobResult[Option[JsObject]]

  def getBlockByNumber(number: BlockNumber, requestId: Int = newRequestId): JobResult[Option[EcBlock]]

  def getBlockByHash(hash: BlockHash, requestId: Int = newRequestId): JobResult[Option[EcBlock]]

  def simulate(blockStateCalls: Seq[BlockStateCall], hash: BlockHash, requestId: Int = newRequestId): JobResult[Seq[JsObject]]

  def getBlockByHashJson(hash: BlockHash, fullTransactionObjects: Boolean = false, requestId: Int = newRequestId): JobResult[Option[JsObject]]

  def getLastExecutionBlock(requestId: Int = newRequestId): JobResult[EcBlock]

  def blockExists(hash: BlockHash, requestId: Int = newRequestId): JobResult[Boolean]

  def getLogs(
      hash: BlockHash,
      addresses: List[EthAddress] = Nil,
      topics: List[String] = Nil,
      requestId: Int = newRequestId
  ): JobResult[List[GetLogsResponseEntry]]

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
    ): JobResult[JsObject] = for {
      targetBlockOpt <- c.getBlockByHash(rollbackTargetBlockId)
      targetBlock    <- targetBlockOpt.toRight(ClientError(s"Target block $rollbackTargetBlockId is not in EC"))
      simulatedBlockJson <- c.simulate(
        EmptyL2Block.mkSimulateCall(targetBlock, feeRecipient, time, prevRandao, withdrawals, depositedTransactions),
        targetBlock.hash
      )
    } yield BlockToPayloadMapper.toPayloadJson(simulatedBlockJson.head, Json.obj("transactions" -> Json.arr(), "withdrawals" -> withdrawals))
  }
}
