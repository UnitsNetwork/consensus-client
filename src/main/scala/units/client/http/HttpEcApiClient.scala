package units.client.http

import cats.syntax.either.*
import cats.syntax.traverse.*
import units.client.JsonRpcClient
import units.client.http.model.*
import units.{BlockHash, ClientConfig, ClientError, Job}
import play.api.libs.json.JsObject
import sttp.client3.*

class HttpEcApiClient(val config: ClientConfig, val backend: SttpBackend[Identity, ?]) extends EcApiClient with JsonRpcClient {

  val apiUrl = uri"${config.executionClientAddress}"

  def getBlockByNumber(number: BlockNumber): Job[Option[EcBlock]] = {
    for {
      json      <- getBlockByNumberJson(number.str)
      blockMeta <- json.traverse(parseJson[EcBlock](_))
    } yield blockMeta
  }

  def getBlockByHash(hash: BlockHash): Job[Option[EcBlock]] = {
    sendRequest[GetBlockByHashRequest, EcBlock](GetBlockByHashRequest(hash, fullTxs = false))
      .leftMap(err => ClientError(s"Error getting block by hash $hash: $err"))
  }

  def getBlockByHashJson(hash: BlockHash, fullTxs: Boolean = false): Job[Option[JsObject]] = {
    sendRequest[GetBlockByHashRequest, JsObject](GetBlockByHashRequest(hash, fullTxs))
      .leftMap(err => ClientError(s"Error getting block json by hash $hash: $err"))
  }

  def getLastExecutionBlock: Job[EcBlock] = for {
    lastEcBlockOpt <- getBlockByNumber(BlockNumber.Latest)
    lastEcBlock    <- Either.fromOption(lastEcBlockOpt, ClientError("Impossible: EC doesn't have blocks"))
  } yield lastEcBlock

  def blockExists(hash: BlockHash): Job[Boolean] =
    getBlockByHash(hash).map(_.isDefined)

  private def getBlockByNumberJson(number: String): Job[Option[JsObject]] = {
    sendRequest[GetBlockByNumberRequest, JsObject](GetBlockByNumberRequest(number))
      .leftMap(err => ClientError(s"Error getting block by number $number: $err"))
  }
  override def getLogs(hash: BlockHash, topic: String): Job[List[GetLogsResponseEntry]] =
    sendRequest[GetLogsRequest, List[GetLogsResponseEntry]](GetLogsRequest(hash, List(topic)))
      .leftMap(err => ClientError(s"Error getting block logs by hash $hash: $err"))
      .map(_.getOrElse(List.empty))
}
