package units.network

import com.google.common.cache.CacheBuilder
import com.wavesplatform.network.ChannelObservable
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.Channel
import io.netty.channel.group.DefaultChannelGroup
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.subjects.ConcurrentSubject
import units.network.BlocksObserverImpl.{BlockWithChannel, State}
import units.{BlockHash, NetworkL2Block}

import java.time.Duration
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.Success

class BlocksObserverImpl(allChannels: DefaultChannelGroup, blocks: ChannelObservable[NetworkL2Block], syncTimeout: FiniteDuration)(implicit
    sc: Scheduler
) extends BlocksObserver,
      ScorexLogging {

  private var state: State = State.Idle(None)
  private val blocksResult: ConcurrentSubject[BlockWithChannel, BlockWithChannel] =
    ConcurrentSubject.publish[BlockWithChannel]

  def requestBlockFromPeers(req: BlockHash): Future[BlockWithChannel] = knownBlockCache.getIfPresent(req) match {
    case null =>
      val p = Promise[BlockWithChannel]()
      sc.execute { () =>
        val candidate = state match {
          case State.RequestingBlock(prevReq, nextAttempt, _) =>
            log.trace(s"No longer requesting block $prevReq, requesting $req instead")
            nextAttempt.cancel()
            None
          case State.Idle(candidate) => candidate
        }
        state = State.RequestingBlock(req, requestFromNextChannel(req, candidate, Set.empty).runToFuture, p)
      }
      p.future
    case (ch, block) =>
      Future.successful(ch -> block)
  }

  private val knownBlockCache = CacheBuilder
    .newBuilder()
    .expireAfterWrite(Duration.ofMinutes(10))
    .maximumSize(100)
    .build[BlockHash, BlockWithChannel]()

  blocks
    .foreach { case v @ (ch, block) =>
      knownBlockCache.put(block.hash, v)
      state match {
        case s @ State.RequestingBlock(expectedHash, nextAttempt, p) =>
          if (expectedHash == block.hash) {
            log.trace(s"Received block ${block.hash}, completing promise")
            nextAttempt.cancel()
            p.complete(Success(ch -> block))
            state = State.Idle(Some(ch))
          } else {
            log.trace(s"Waiting for block ${s.blockHash}, got ${block.hash} instead")
            blocksResult.onNext(v)
          }
        case _ =>
      }
    }

  def blockStream: ChannelObservable[NetworkL2Block] = blocksResult

  private def requestFromNextChannel(req: BlockHash, candidate: Option[Channel], excluded: Set[Channel]): Task[Unit] = Task {
    candidate.filterNot(excluded).orElse(nextOpenChannel(excluded)) match {
      case None =>
        log.trace(s"No channel to request $req")
        Set.empty[Channel]
      case Some(ch) =>
        ch.writeAndFlush(GetBlock(req))
        excluded ++ Set(ch)
    }
  }.flatMap(newExcluded => requestFromNextChannel(req, candidate, newExcluded).delayExecution(syncTimeout))

  private def nextOpenChannel(exclude: Set[Channel]): Option[Channel] =
    allChannels.asScala.find(c => !exclude(c) && c.isOpen)

}

object BlocksObserverImpl {

  type BlockWithChannel = (Channel, NetworkL2Block)

  enum State {
    case RequestingBlock(blockHash: BlockHash, nextAttempt: Cancelable, result: Promise[BlockWithChannel])
    case Idle(pinnedChannel: Option[Channel])
  }
}
