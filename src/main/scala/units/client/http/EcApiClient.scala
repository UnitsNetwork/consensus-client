package units.client.http

import units.client.http.model.*
import units.{BlockHash, Job}
import play.api.libs.json.JsObject

trait EcApiClient {
  def getBlockByNumber(number: BlockNumber): Job[Option[EcBlock]]

  def getBlockByHash(hash: BlockHash): Job[Option[EcBlock]]

  def getBlockByHashJson(hash: BlockHash, fullTxs: Boolean = false): Job[Option[JsObject]]

  def getLastExecutionBlock: Job[EcBlock]

  def blockExists(hash: BlockHash): Job[Boolean]

  def getLogs(hash: BlockHash, topic: String): Job[List[GetLogsResponseEntry]]
}
