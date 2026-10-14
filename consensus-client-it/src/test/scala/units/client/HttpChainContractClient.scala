package units.client

import cats.syntax.option.*
import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.api.LoggingBackend.LoggingOptions
import com.wavesplatform.api.NodeHttpApi
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.state.{DataEntry, Height}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.utils.ScorexLogging
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import units.BlockHash
import units.client.contract.ChainContractClient.DefaultMainChainId
import units.client.contract.{ChainContractClient, ContractBlock}
import units.docker.WavesNodeContainer
import units.test.IntegrationTestEventually

import scala.annotation.tailrec

class HttpChainContractClient(api: NodeHttpApi, override val contract: Address)
    extends ChainContractClient
    with IntegrationTestEventually
    with Matchers
    with OptionValues
    with ScorexLogging {
  override def extractData(key: String): Option[DataEntry[?]] = api.dataByKey(contract, key)(using LoggingOptions(logRequest = false))

  private val fiveBlocks              = WavesNodeContainer.MaxBlockDelay * 5
  lazy val nativeTokenId: IssuedAsset = IssuedAsset(ByteStr.decodeBase58(getStringData("tokenId").getOrElse(fail("Call setup first"))).get)

  def getEpochFirstBlock(epochNumber: Height): Option[ContractBlock] =
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

  def waitForMinerEpoch(minerAccount: KeyPair)(implicit loggingOptions: LoggingOptions = LoggingOptions(logRequest = false)): Unit = {
    val expectedGenerator = minerAccount.toAddress
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} waitMinerEpoch($expectedGenerator)")

    val subsequentLoggingOptions = loggingOptions.copy(logCall = false)
    eventually(timeout(fiveBlocks)) {
      val actualGenerator = computedGenerator()(using subsequentLoggingOptions)
      actualGenerator shouldBe expectedGenerator
    }
  }

  /** Waits for an epoch in which `minerAccount` is the designated miner and no block has been registered yet. Unlike [[waitForMinerEpoch]], it
    * doesn't return in an epoch that changed hands midway, e.g. after the previous miner left having already mined in it: the epoch's blocks then
    * belong to the previous miner, and `minerAccount` can only mine in a later epoch.
    */
  def waitForUnusedMinerEpoch(minerAccount: KeyPair)(implicit loggingOptions: LoggingOptions = LoggingOptions(logRequest = false)): Unit = {
    val expectedGenerator = minerAccount.toAddress
    if (loggingOptions.logCall) log.debug(s"${loggingOptions.prefix} waitForUnusedMinerEpoch($expectedGenerator)")

    val subsequentLoggingOptions = loggingOptions.copy(logCall = false)
    eventually(timeout(fiveBlocks * 2)) {
      val epoch = api.height()(using subsequentLoggingOptions)
      computedGenerator()(using subsequentLoggingOptions) shouldBe expectedGenerator
      withClue(s"Blocks registered in epoch $epoch: ")(getEpochMeta(epoch) shouldBe empty)
      withClue("Epoch changed during the check: ")(api.height()(using subsequentLoggingOptions) shouldBe epoch)
    }
  }

  // This is different from blockchain.height, because we wait this from a contract block
  def waitForEpoch(atLeast: Height, chainId: Long = DefaultMainChainId): Unit = {
    log.debug(s"waitForEpoch($atLeast)")
    eventually {
      val lastBlock = getLastBlockMeta(chainId).value
      lastBlock.epoch should be >= atLeast
    }
  }

  def waitForHeight(atLeast: Long, chainId: Long = DefaultMainChainId): Unit = {
    log.debug(s"waitForHeight($atLeast)")
    eventually {
      val lastBlock = getLastBlockMeta(chainId).value
      lastBlock.height should be >= atLeast
    }
  }

  def waitForChainId(chainId: Long): Unit = {
    log.debug(s"waitForChainId($chainId)")
    eventually {
      getFirstBlockHash(chainId) shouldBe defined
    }
  }

  def computedGenerator()(implicit loggingOptions: LoggingOptions): Address = {
    if (loggingOptions.logCall) log.debug("computedGenerator")
    val rawResult  = api.evaluateExpr(contract, "computedGenerator").result
    val rawAddress = (rawResult \ "result" \ "value").as[String]
    Address.fromString(rawAddress) match {
      case Left(e)  => fail(s"Can't parse computedGenerator address: $rawAddress. Reason: $e")
      case Right(r) => r
    }
  }
}
