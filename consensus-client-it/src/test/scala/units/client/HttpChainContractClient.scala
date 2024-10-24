package units.client

import com.wavesplatform.account.Address
import com.wavesplatform.api.NodeHttpApi
import com.wavesplatform.state.DataEntry
import units.BlockHash
import cats.syntax.option.*
import units.client.contract.{ChainContractClient, ContractBlock}

import scala.annotation.tailrec

class HttpChainContractClient(api: NodeHttpApi, override val contract: Address) extends ChainContractClient {
  override def extractData(key: String): Option[DataEntry[?]] = api.getDataByKey(contract, key)

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
