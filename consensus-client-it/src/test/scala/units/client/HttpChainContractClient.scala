package units.client

import cats.syntax.option.*
import com.wavesplatform.account.Address
import com.wavesplatform.api.NodeHttpApi
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.state.DataEntry
import com.wavesplatform.transaction.Asset.IssuedAsset
import units.BlockHash
import units.client.contract.{ChainContractClient, ContractBlock}

import scala.annotation.tailrec

class HttpChainContractClient(api: NodeHttpApi, override val contract: Address) extends ChainContractClient {
  override def extractData(key: String): Option[DataEntry[?]] = api.dataByKey(contract, key)

  lazy val token: IssuedAsset = IssuedAsset(ByteStr.decodeBase58(getStringData("tokenId").getOrElse(fail("Call setup first"))).get)

  def computedGenerator: Address = {
    val rawResult  = api.evaluateExpr(contract, "computedGenerator").result
    val rawAddress = (rawResult \ "result" \ "value").as[String]
    Address.fromString(rawAddress) match {
      case Left(e)  => fail(s"Can't parse computedGenerator address: $rawAddress. Reason: $e")
      case Right(r) => r
    }
  }

  def getEpochFirstBlock(epochNumber: Int): Option[ContractBlock] =
    getEpochMeta(epochNumber).flatMap { epochData =>
      getEpochFirstBlock(epochData.lastBlockHash)
    }

  def getEpochFirstBlock(blockHashInEpoch: BlockHash): Option[ContractBlock] = {
    @tailrec
    def loop(lastBlock: ContractBlock): Option[ContractBlock] =
      getBlock(lastBlock.parentHash) match {
        case Some(blockData) =>
          if (blockData.height == 0) blockData.some
          else if (blockData.epoch == lastBlock.epoch) loop(blockData)
          else lastBlock.some

        case None => none
      }

    getBlock(blockHashInEpoch).flatMap { blockData =>
      if (blockData.height == 0) blockData.some
      else loop(blockData)
    }
  }
}
