package units

import cats.syntax.either.*
import com.wavesplatform.account.{Address, KeyPair, PublicKey, SeedKeyPair}
import com.wavesplatform.api.common.{CommonAccountsApi, CommonAssetsApi, CommonBlocksApi, CommonTransactionsApi}
import com.wavesplatform.api.http.requests.InvokeScriptRequest.FunctionCallPart
import com.wavesplatform.api.http.utils.UtilsEvaluator
import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.database.{RDB, RocksDBWriter}
import com.wavesplatform.events.UtxEvent
import com.wavesplatform.extensions.Context
import com.wavesplatform.history.Domain
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.mining.MultiDimensionalMiningConstraint
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state.{Blockchain, BlockchainUpdaterImpl, StringDataEntry, TxMeta}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.smart.script.trace.TracedResult
import com.wavesplatform.transaction.{DiscardedBlocks, Transaction}
import com.wavesplatform.utils.{ScorexLogging, Time}
import com.wavesplatform.utx.UtxPool
import com.wavesplatform.wallet.Wallet
import io.netty.channel.Channel
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.eval.Task
import monix.execution.ExecutionModel
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import net.ceedubs.ficus.Ficus.*
import org.scalatest.exceptions.TestFailedException
import org.web3j.abi.datatypes.generated.Uint256
import play.api.libs.json.*
import units.ELUpdater.*
import units.ELUpdater.State.{ChainStatus, Working}
import units.ExtensionDomain.*
import units.client.contract.HasConsensusLayerDappTxHelpers
import units.client.contract.HasConsensusLayerDappTxHelpers.EmptyE2CTransfersRootHashHex
import units.client.engine.model.{EcBlock, TestEcBlocks}
import units.client.{L2BlockLike, TestEcClients}
import units.eth.{EthAddress, EthereumConstants, Gwei}
import units.network.TestBlocksObserver
import units.test.CustomMatchers

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.reflect.ClassTag

class ExtensionDomain(
    rdb: RDB,
    blockchainUpdater: BlockchainUpdaterImpl,
    rocksDBWriter: RocksDBWriter,
    settings: WavesSettings,
    override val elBridgeAddress: EthAddress,
    elMinerDefaultReward: Gwei
) extends Domain(rdb, blockchainUpdater, rocksDBWriter, settings)
    with HasConsensusLayerDappTxHelpers
    with CustomMatchers
    with AutoCloseable
    with ScorexLogging { self =>
  override val chainContractAccount: KeyPair   = KeyPair("chain-contract".getBytes(StandardCharsets.UTF_8))
  override val stakingContractAccount: KeyPair = KeyPair("staking-contract".getBytes(StandardCharsets.UTF_8))

  val l2Config = settings.config.as[ClientConfig]("waves.l2")
  require(l2Config.chainContractAddress == chainContractAddress, "Check settings")

  val ecGenesisBlock = EcBlock(
    hash = TestEcBlockBuilder.createBlockHash(""),
    parentHash = BlockHash(EthereumConstants.EmptyBlockHashHex), // see main.ride
    stateRoot = EthereumConstants.EmptyRootHashHex,
    height = 0,
    timestamp = testTime.getTimestamp() / 1000 - l2Config.blockDelay.toSeconds,
    minerRewardL2Address = EthAddress.empty,
    baseFeePerGas = Uint256.DEFAULT,
    gasLimit = 0,
    gasUsed = 0,
    prevRandao = EthereumConstants.EmptyPrevRandaoHex,
    withdrawals = Vector.empty
  )

  val ecClients = new TestEcClients(ecGenesisBlock, blockchain)

  val globalScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
  val eluScheduler    = TestScheduler(ExecutionModel.AlwaysAsyncExecution)

  val elBlockStream = PublishSubject[(Channel, NetworkL2Block)]()
  val blockObserver = new TestBlocksObserver(elBlockStream)

  val neighbourChannel = new EmbeddedChannel()
  val allChannels      = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
  allChannels.add(neighbourChannel)
  def pollSentNetworkBlock(): Option[NetworkL2Block] = Option(neighbourChannel.readOutbound[NetworkL2Block])
  def receiveNetworkBlock(ecBlock: EcBlock, miner: SeedKeyPair, epochNumber: Int = blockchain.height): Unit =
    receiveNetworkBlock(toNetworkBlock(ecBlock, miner, epochNumber))
  def receiveNetworkBlock(incomingNetworkBlock: NetworkL2Block): Unit = elBlockStream.onNext((new EmbeddedChannel(), incomingNetworkBlock))

  val extensionContext = new Context {
    override def settings: WavesSettings = self.settings
    override def blockchain: Blockchain  = self.blockchain

    override def rollbackTo(blockId: ByteStr): Task[Either[ValidationError, DiscardedBlocks]]  = Task(blockchainUpdater.removeAfter(blockId))
    override def broadcastTransaction(tx: Transaction): TracedResult[ValidationError, Boolean] = utx.putIfNew(tx)

    override def time: Time     = self.testTime
    override def utx: UtxPool   = self.utxPool
    override def wallet: Wallet = self.wallet

    override def transactionsApi: CommonTransactionsApi = self.transactionsApi
    override def blocksApi: CommonBlocksApi             = self.blocksApi
    override def accountsApi: CommonAccountsApi         = self.accountsApi
    override def assetsApi: CommonAssetsApi             = self.assetsApi

    override def utxEvents: Observable[UtxEvent] = Observable.empty
  }

  val consensusClient: ConsensusClient = new ConsensusClient(
    l2Config,
    extensionContext,
    ecClients.engineApi,
    blockObserver,
    allChannels,
    globalScheduler,
    eluScheduler,
    () => {}
  )
  triggers = triggers.appended(consensusClient)

  val defaultMaxTimeout =
    List(WaitForReferenceConfirmInterval, ClChangedProcessingDelay, MiningRetryInterval, WaitRequestedBlockTimeout).max + 1.millis
  val defaultInterval = ClChangedProcessingDelay

  def waitForWorking(
      title: String = "",
      maxTimeout: FiniteDuration = defaultMaxTimeout,
      interval: FiniteDuration = defaultInterval
  )(f: Working[?] => Unit): Unit = {
    val logPrefix = if (title.isEmpty) "waitForWorking" else s"waitForWorking($title)"
    log.trace(logPrefix)
    val maxAttempts = (maxTimeout / interval).toInt
    @tailrec
    def loop(currAttempt: Int, lastException: Throwable): Unit = {
      currAttempt match {
        case 0                     => // Do nothing
        case 1                     => triggerScheduledTasks(silent = true)
        case x if x <= maxAttempts => advanceAll(interval, silent = true)
        case _ =>
          log.warn(s"$logPrefix: $maxAttempts attempts are out")
          throw lastException
      }

      try {
        f(is[Working[?]](consensusClient.elu.state))
        log.trace(s"$logPrefix: successful after ${interval * math.max(0, currAttempt - 1)} (${currAttempt - 1} attempts)")
      } catch {
        case e: Throwable => loop(currAttempt + 1, e)
      }
    }

    loop(0, new TestFailedException(s"$maxAttempts attempts are out", 0))
  }

  def waitForCS[CS <: ChainStatus: ClassTag](title: String = "")(f: CS => Unit): Unit = waitForWorking(title) { s =>
    f(is[CS](s.chainStatus))
  }

  def toNetworkBlock(ecBlock: EcBlock, miner: SeedKeyPair, epochNumber: Int): NetworkL2Block =
    NetworkL2Block
      .signed(
        TestEcBlocks.toPayload(
          ecBlock,
          calculateRandao(
            blockchain.vrf(epochNumber).getOrElse(throw new RuntimeException(s"VRF is empty for epoch $epochNumber")),
            ecBlock.parentHash
          )
        ),
        miner.privateKey
      )
      .explicitGet()

  def forgeFromUtxPool(): Unit = {
    val (txsOpt, _, _) = utxPool.packUnconfirmed(MultiDimensionalMiningConstraint.Unlimited, None)
    txsOpt match {
      case None      => throw new RuntimeException("Can't pack transactions from UTX pool")
      case Some(txs) => appendMicroBlockAndVerify(txs*)
    }
  }

  def appendMicroBlockAndVerify(txs: Transaction*): BlockId = {
    val blockId = appendMicroBlock(txs*)
    txs.foreach { tx =>
      val meta = blockchain.transactionMeta(tx.id())
      if (!meta.fold(false)(_.status == TxMeta.Status.Succeeded))
        throw new RuntimeException(s"Expected ${tx.id()} to be succeeded in block $blockId, got: $meta")
    }
    blockId
  }

  def advanceNewBlocks(expectedElGenerator: Address, attempts: Int = 100): Unit =
    if (attempts == 0) throw new RuntimeException(s"Can't advance blocks so EL generator is $expectedElGenerator: all attempts are out!")
    else {
      appendBlock()
      val generator = evaluatedComputedGenerator
      if (generator == expectedElGenerator) testTime.advance(l2Config.blockDelay)
      else {
        log.debug(s"advanceNewBlocks: unexpected computed generator $generator")
        advanceNewBlocks(expectedElGenerator, attempts - 1)
      }
    }

  lazy val token: IssuedAsset = blockchain.accountData(chainContractAddress, "tokenId") match {
    case Some(StringDataEntry(_, tokenId)) =>
      IssuedAsset(ByteStr.decodeBase58(tokenId).getOrElse(throw new RuntimeException(s"Unexpected token id: $tokenId")))
    case x => throw new RuntimeException(s"Unexpected token id entry: $x")
  }

  def evaluatedFinalizedBlock: JsObject =
    (evaluate(chainContractAddress, """blockMeta(getStringValue("finBlock"))""") \ "result" \ "value").as[JsObject]

  def evaluatedComputedGenerator: Address = parseResultValue[Address](evaluate(chainContractAddress, "computedGenerator"))

  private def parseResultValue[T: Reads](o: JsObject)(implicit ct: ClassTag[T]): T =
    (o \ "result" \ "value").asOpt[T] match {
      case Some(r) => r
      case None    => throw new RuntimeException(s"Can't parse ${ct.runtimeClass.getSimpleName} from result.value of $o")
    }

  // Useful for debugging purposes
  def evaluateExtendAltChain(
      minerAccount: KeyPair,
      chainId: Long,
      block: L2BlockLike,
      epoch: Long,
      e2CTransfersRootHashHex: String = EmptyE2CTransfersRootHashHex
  ): Either[String, JsObject] = {
    val r = evaluate(
      chainContractAddress,
      FunctionCallPart(
        "extendAltChain",
        List[Terms.EVALUATED](
          Terms.CONST_LONG(chainId),
          Terms.CONST_STRING(block.hash.drop(2)).explicitGet(),
          Terms.CONST_STRING(block.parentHash.drop(2)).explicitGet(),
          Terms.CONST_LONG(epoch),
          Terms.CONST_STRING(e2CTransfersRootHashHex.drop(2)).explicitGet()
        )
      ),
      minerAccount.publicKey,
      debug = false
    )

    (r \ "message").asOpt[String] match {
      case Some(e) => e.asLeft
      case None    => r.asRight
    }
  }

  def evaluate(dApp: Address, expr: String, debug: Boolean = false): JsObject = UtilsEvaluator.evaluate(
    blockchain,
    dApp,
    Json.obj("expr" -> expr),
    UtilsEvaluator.EvaluateOptions(
      evaluateScriptComplexityLimit = Int.MaxValue,
      maxTxErrorLogSize = if (debug) Int.MaxValue else 0,
      enableTraces = debug,
      intAsString = false
    )
  )

  def evaluate(dApp: Address, call: FunctionCallPart, sender: PublicKey, debug: Boolean): JsObject =
    UtilsEvaluator.evaluate(
      blockchain,
      dApp,
      Json.obj(
        "call"            -> call,
        "sender"          -> sender.toAddress.toString,
        "senderPublicKey" -> sender.toString
      ),
      UtilsEvaluator.EvaluateOptions(
        evaluateScriptComplexityLimit = Int.MaxValue,
        maxTxErrorLogSize = if (debug) Int.MaxValue else 0,
        enableTraces = debug,
        intAsString = false
      )
    )

  // See ELUpdater.consensusLayerChanged
  def advanceConsensusLayerChanged(): Unit = advanceElu(ELUpdater.ClChangedProcessingDelay, "advanceConsensusLayerChanged")

  // See ELUpdater.requestBlocksAndStartMining
  def advanceWaitRequestedBlock(): Unit = advanceElu(ELUpdater.WaitRequestedBlockTimeout, "advanceWaitRequestedBlock")

  def advanceMiningRetry(): Unit = advanceElu(ELUpdater.MiningRetryInterval, "advanceMiningRetry")

  def advanceMining(): Unit = advanceElu(l2Config.blockDelay, "advanceMining")

  def advanceElu(x: FiniteDuration, name: String = "advanceElu"): Unit = {
    log.trace(s"$name($x)")
    testTime.advance(x)
    eluScheduler.tick(x)
  }

  def triggerScheduledTasks(silent: Boolean = false): Unit = {
    if (!silent) log.trace("triggerScheduledTasks")
    advanceAllTasks(0.seconds)
  }

  def advanceAll(x: FiniteDuration, silent: Boolean = false): Unit = {
    if (!silent) log.trace("advanceAll")
    advanceAllTasks(x)
  }

  private def advanceAllTasks(x: FiniteDuration, triggerSchedulers: Boolean = true): Unit = {
    testTime.advance(x)
    if (triggerSchedulers) {
      globalScheduler.tick(x)
      eluScheduler.tick(x)
    }
  }

  def logTasks(): Unit = {
    log.trace("Logging tasks")
    def l(name: String, s: TestScheduler): Unit = {
      val tasksStr = if (s.state.tasks.isEmpty) "no tasks" else s.state.tasks.mkString(", ")
      log.trace(s"$name tasks: $tasksStr")
    }

    l("ELUpdater", eluScheduler)
    l("Global", globalScheduler)
  }

  override def close(): Unit = {
    log.trace("close")
    val r = consensusClient.shutdown()
    triggerScheduledTasks()
    Await.result(r, 10.seconds)
    utxPool.close()
  }

  def createEcBlockBuilder(hashPath: String, miner: ElMinerSettings, parent: EcBlock = ecGenesisBlock): TestEcBlockBuilder =
    createEcBlockBuilder(hashPath, miner.elRewardAddress, parent)

  def createEcBlockBuilder(hashPath: String, minerRewardL2Address: EthAddress, parent: EcBlock): TestEcBlockBuilder = {
    TestEcBlockBuilder(ecClients, elBridgeAddress, elMinerDefaultReward, l2Config.blockDelay, parent = parent).updateBlock(
      _.copy(
        hash = TestEcBlockBuilder.createBlockHash(hashPath),
        minerRewardL2Address = minerRewardL2Address,
        prevRandao = ELUpdater.calculateRandao(blockchain.vrf(blockchain.height).get, parent.hash)
      )
    )
  }

  override def currentHitSource: BlockId =
    blockchain.hitSource(blockchain.height).getOrElse(throw new RuntimeException(s"Can't get hit source for ${blockchain.height}"))
}

object ExtensionDomain {
  implicit val evaluatedWrites: Writes[Terms.EVALUATED] = new Writes[Terms.EVALUATED] {
    override def writes(x: Terms.EVALUATED): JsValue = x match {
      case Terms.CONST_LONG(x)    => Json.obj("type" -> "integer", "value" -> x)
      case Terms.CONST_BIGINT(x)  => Json.obj("type" -> "integer", "value" -> x.toString)
      case x: Terms.CONST_BYTESTR => Json.obj("type" -> "binary", "value" -> x.bs.base64)
      case x: Terms.CONST_STRING  => Json.obj("type" -> "string", "value" -> x.s)
      case Terms.CONST_BOOLEAN(x) => Json.obj("type" -> "boolean", "value" -> x)
      case Terms.ARR(xs)          => Json.obj("type" -> "list", "value" -> xs.map(writes))
      case x                      => throw new RuntimeException(s"Can't serialize $x")
    }
  }

  implicit val functionCallPartWrites: Writes[FunctionCallPart] = Json.writes
}
