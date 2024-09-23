package units.network

import com.google.common.cache.CacheBuilder
import com.wavesplatform.network.ChannelObservable
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.Channel
import io.netty.channel.group.DefaultChannelGroup
import monix.eval.Task
import monix.execution.{Cancelable, CancelableFuture, CancelablePromise, Scheduler}
import monix.reactive.subjects.ConcurrentSubject
import units.network.PayloadObserverImpl.{PayloadInfoWithChannel, State}
import units.{BlockHash, ExecutionPayloadInfo}

import java.time.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

class PayloadObserverImpl(allChannels: DefaultChannelGroup, payloads: ChannelObservable[ExecutionPayloadInfo], syncTimeout: FiniteDuration)(implicit
    sc: Scheduler
) extends PayloadObserver
    with ScorexLogging {

  private var state: State = State.Idle(None)
  private val payloadsResult: ConcurrentSubject[PayloadInfoWithChannel, PayloadInfoWithChannel] =
    ConcurrentSubject.publish[PayloadInfoWithChannel]

  private val knownPayloadCache = CacheBuilder
    .newBuilder()
    .expireAfterWrite(Duration.ofMinutes(10))
    .maximumSize(100)
    .build[BlockHash, PayloadInfoWithChannel]()

  payloads
    .foreach { case v @ (ch, epi) =>
      state = state match {
        case State.LoadingPayload(expectedHash, nextAttempt, p) if expectedHash == epi.payload.hash =>
          nextAttempt.cancel()
          p.complete(Success(ch -> epi))
          State.Idle(Some(ch))
        case other => other
      }

      knownPayloadCache.put(epi.payload.hash, v)
      payloadsResult.onNext(v)
    }

  def loadPayload(req: BlockHash): CancelableFuture[PayloadInfoWithChannel] = {
    log.info(s"Loading payload $req")
    knownPayloadCache.getIfPresent(req) match {
      case null =>
        val p = CancelablePromise[PayloadInfoWithChannel]()
        sc.execute { () =>
          val candidate = state match {
            case State.LoadingPayload(_, nextAttempt, promise) =>
              nextAttempt.cancel()
              promise.complete(Failure(new NoSuchElementException("Loading was canceled")))
              None
            case State.Idle(candidate) => candidate
          }
          state = State.LoadingPayload(req, requestFromNextChannel(req, candidate, Set.empty).runToFuture, p)
        }
        p.future
      case (ch, pm) =>
        CancelablePromise.successful(ch -> pm).future
    }
  }

  def getPayloadStream: ChannelObservable[ExecutionPayloadInfo] = payloadsResult

  // TODO: remove Task
  private def requestFromNextChannel(req: BlockHash, candidate: Option[Channel], excluded: Set[Channel]): Task[Unit] = Task {
    candidate.filterNot(excluded).orElse(nextOpenChannel(excluded)) match {
      case None =>
        log.trace(s"No channel to request $req")
        Set.empty[Channel]
      case Some(ch) =>
        ch.writeAndFlush(GetPayload(req))
        excluded ++ Set(ch)
    }
  }.flatMap(newExcluded => requestFromNextChannel(req, candidate, newExcluded).delayExecution(syncTimeout))

  private def nextOpenChannel(exclude: Set[Channel]): Option[Channel] =
    allChannels.asScala.find(c => !exclude(c) && c.isOpen)

}

object PayloadObserverImpl {

  type PayloadInfoWithChannel = (Channel, ExecutionPayloadInfo)

  sealed trait State

  object State {
    case class LoadingPayload(blockHash: BlockHash, nextAttempt: Cancelable, promise: CancelablePromise[PayloadInfoWithChannel]) extends State

    case class Idle(pinnedChannel: Option[Channel]) extends State
  }

}
