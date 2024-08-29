package units.client.http

import units.client.http.model.{BlockNumber, EcBlock, GetLogsResponseEntry}
import units.{BlockHash, HasJobLogging, Job}
import play.api.libs.json.JsObject

class LoggedEcApiClient(underlying: EcApiClient) extends EcApiClient with HasJobLogging {
  override def getBlockByNumber(number: BlockNumber): Job[Option[EcBlock]] = wrap(s"getBlockByNumber($number)", underlying.getBlockByNumber(number))

  override def getBlockByHash(hash: BlockHash): Job[Option[EcBlock]] = wrap(s"getBlockByHash($hash)", underlying.getBlockByHash(hash))

  override def getBlockByHashJson(hash: BlockHash, fullTxs: Boolean): Job[Option[JsObject]] =
    wrap(s"getBlockByHashJson($hash, fullTxs=$fullTxs)", underlying.getBlockByHashJson(hash, fullTxs))

  override def getLastExecutionBlock: Job[EcBlock] = wrap("getLastExecutionBlock", underlying.getLastExecutionBlock)

  override def blockExists(hash: BlockHash): Job[Boolean] = wrap(s"blockExists($hash)", underlying.blockExists(hash))

  override def getLogs(hash: BlockHash, topic: String): Job[List[GetLogsResponseEntry]] =
    wrap(s"getLogs($hash, $topic)", underlying.getLogs(hash, topic))
}

object LoggedEcApiClient {
  def apply(underlying: EcApiClient): EcApiClient = new LoggedEcApiClient(underlying)
}
