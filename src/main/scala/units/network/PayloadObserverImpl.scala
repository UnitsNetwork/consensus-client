package units.network

import com.google.common.cache.CacheBuilder
import com.wavesplatform.network.ChannelObservable
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.Channel
import io.netty.channel.group.DefaultChannelGroup
import monix.eval.Task
import monix.execution.{Cancelable, CancelableFuture, CancelablePromise, Scheduler}
import monix.reactive.subjects.ConcurrentSubject
import units.network.PayloadObserverImpl.{PayloadWithChannel, State}
import units.BlockHash

import java.time.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

class PayloadObserverImpl(allChannels: DefaultChannelGroup, payloads: ChannelObservable[PayloadMessage], syncTimeout: FiniteDuration)(implicit
    sc: Scheduler
) extends PayloadObserver
    with ScorexLogging {

  private var state: State = State.Idle(None)
  private val payloadsResult: ConcurrentSubject[PayloadWithChannel, PayloadWithChannel] =
    ConcurrentSubject.publish[PayloadWithChannel]

  private val knownPayloadCache = CacheBuilder
    .newBuilder()
    .expireAfterWrite(Duration.ofMinutes(10))
    .maximumSize(100)
    .build[BlockHash, PayloadWithChannel]()

  payloads
    .foreach { case v @ (ch, pm) =>
      state = state match {
        case State.LoadingPayload(expectedHash, nextAttempt, p) if expectedHash == pm.hash =>
          nextAttempt.cancel()
          p.complete(Success(ch -> pm))
          State.Idle(Some(ch))
        case other => other
      }

      knownPayloadCache.put(pm.hash, v)
      payloadsResult.onNext(v)
    }

  def loadPayload(req: BlockHash): CancelableFuture[PayloadWithChannel] = knownPayloadCache.getIfPresent(req) match {
    case null =>
      val p = CancelablePromise[PayloadWithChannel]()
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

  def getPayloadStream: ChannelObservable[PayloadMessage] = payloadsResult

  def requestPayload(req: BlockHash): Task[PayloadWithChannel] = Task
    .defer {
      log.info(s"Loading payload $req")
      knownPayloadCache.getIfPresent(req) match {
        case null =>
          val p = CancelablePromise[PayloadWithChannel]()

          val candidate = state match {
            case l: State.LoadingPayload =>
              log.trace(s"No longer waiting for payload ${l.blockHash}, will load $req instead")
              l.nextAttempt.cancel()
              l.promise.future.cancel()
              None
            case State.Idle(candidate) =>
              candidate
          }

          state = State.LoadingPayload(
            req,
            requestFromNextChannel(req, candidate, Set.empty).runToFuture,
            p
          )

          Task.fromCancelablePromise(p)
        case (ch, pm) =>
          Task.pure(ch -> pm)
      }
    }
    .executeOn(sc)

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

  type PayloadWithChannel = (Channel, PayloadMessage)

  sealed trait State

  object State {
    case class LoadingPayload(blockHash: BlockHash, nextAttempt: Cancelable, promise: CancelablePromise[PayloadWithChannel]) extends State

    case class Idle(pinnedChannel: Option[Channel]) extends State
  }

}
