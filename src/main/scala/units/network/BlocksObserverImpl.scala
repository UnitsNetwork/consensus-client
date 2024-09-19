package units.network

import com.google.common.cache.CacheBuilder
import com.wavesplatform.network.ChannelObservable
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.Channel
import io.netty.channel.group.DefaultChannelGroup
import monix.eval.Task
import monix.execution.{Cancelable, CancelableFuture, CancelablePromise, Scheduler}
import monix.reactive.subjects.ConcurrentSubject
import units.network.BlocksObserverImpl.{BlockWithChannel, State}
import units.{BlockHash, NetworkBlock}

import java.time.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

class BlocksObserverImpl(allChannels: DefaultChannelGroup, blocks: ChannelObservable[NetworkBlock], syncTimeout: FiniteDuration)(implicit
                                                                                                                                 sc: Scheduler
) extends BlocksObserver
  with ScorexLogging {

  private var state: State = State.Idle(None)
  private val blocksResult: ConcurrentSubject[BlockWithChannel, BlockWithChannel] =
    ConcurrentSubject.publish[BlockWithChannel]

  def loadBlock(req: BlockHash): CancelableFuture[BlockWithChannel] = knownBlockCache.getIfPresent(req) match {
    case null =>
      val p = CancelablePromise[BlockWithChannel]()
      sc.execute { () =>
        val candidate = state match {
          case State.LoadingBlock(_, nextAttempt, promise) =>
            nextAttempt.cancel()
            promise.complete(Failure(new NoSuchElementException("Loading was canceled")))
            None
          case State.Idle(candidate) => candidate
        }
        state = State.LoadingBlock(req, requestFromNextChannel(req, candidate, Set.empty).runToFuture, p)
      }
      p.future
    case (ch, block) =>
      CancelablePromise.successful(ch -> block).future
  }

  private val knownBlockCache = CacheBuilder
    .newBuilder()
    .expireAfterWrite(Duration.ofMinutes(10))
    .maximumSize(100)
    .build[BlockHash, BlockWithChannel]()

  blocks
    .foreach { case v@(ch, block) =>
      state = state match {
        case State.LoadingBlock(expectedHash, nextAttempt, p) if expectedHash == block.hash =>
          nextAttempt.cancel()
          p.complete(Success(ch -> block))
          State.Idle(Some(ch))
        case other => other
      }

      knownBlockCache.put(block.hash, v)
      blocksResult.onNext(v)
    }

  def getBlockStream: ChannelObservable[NetworkBlock] = blocksResult

  def requestBlock(req: BlockHash): Task[BlockWithChannel] = Task
    .defer {
      log.info(s"Loading block $req")
      knownBlockCache.getIfPresent(req) match {
        case null =>
          val p = CancelablePromise[BlockWithChannel]()

          val candidate = state match {
            case l: State.LoadingBlock =>
              log.trace(s"No longer waiting for block ${l.blockHash}, will load $req instead")
              l.nextAttempt.cancel()
              l.promise.future.cancel()
              None
            case State.Idle(candidate) =>
              candidate
          }

          state = State.LoadingBlock(
            req,
            requestFromNextChannel(req, candidate, Set.empty).runToFuture,
            p
          )

          Task.fromCancelablePromise(p)
        case (ch, block) =>
          Task.pure(ch -> block)
      }
    }
    .executeOn(sc)

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

  type BlockWithChannel = (Channel, NetworkBlock)

  sealed trait State

  object State {
    case class LoadingBlock(blockHash: BlockHash, nextAttempt: Cancelable, promise: CancelablePromise[BlockWithChannel]) extends State

    case class Idle(pinnedChannel: Option[Channel]) extends State
  }

}
