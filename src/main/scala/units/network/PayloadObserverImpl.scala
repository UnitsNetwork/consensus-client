package units.network

import cats.syntax.either.*
import com.google.common.cache.CacheBuilder
import com.wavesplatform.account.{PrivateKey, PublicKey}
import com.wavesplatform.network.ChannelGroupExt
import com.wavesplatform.utils.ScorexLogging
import io.netty.channel.Channel
import io.netty.channel.group.DefaultChannelGroup
import monix.eval.Task
import monix.execution.{Cancelable, CancelableFuture, CancelablePromise, Scheduler}
import monix.reactive.Observable
import monix.reactive.subjects.ConcurrentSubject
import play.api.libs.json.JsObject
import units.eth.EthAddress
import units.network.PayloadObserverImpl.State
import units.{BlockHash, ExecutionPayloadInfo}

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class PayloadObserverImpl(
    allChannels: DefaultChannelGroup,
    payloads: Observable[PayloadMessageWithChannel],
    initialMinersKeys: Map[EthAddress, PublicKey],
    syncTimeout: FiniteDuration
)(implicit sc: Scheduler)
    extends PayloadObserver
    with ScorexLogging {

  private var state: State = State.Idle(None)
  private val lastPayloadMessages: ConcurrentHashMap[BlockHash, PayloadMessageWithChannel] =
    new ConcurrentHashMap[BlockHash, PayloadMessageWithChannel]()

  private val payloadsResult: ConcurrentSubject[ExecutionPayloadInfo, ExecutionPayloadInfo] =
    ConcurrentSubject.publish[ExecutionPayloadInfo]
  private val addrToPK: ConcurrentHashMap[EthAddress, PublicKey] = new ConcurrentHashMap[EthAddress, PublicKey](initialMinersKeys.asJava)

  private val knownPayloadCache = CacheBuilder
    .newBuilder()
    .expireAfterWrite(Duration.ofMinutes(10))
    .maximumSize(100)
    .build[BlockHash, ExecutionPayloadInfo]()

  payloads
    .foreach { case v @ PayloadMessageWithChannel(pm, ch) =>
      state = state match {
        case State.LoadingPayload(expectedHash, nextAttempt, p) if expectedHash == pm.hash =>
          pm.payloadInfo match {
            case Right(epi) =>
              nextAttempt.cancel()
              p.complete(Success(epi))
              lastPayloadMessages.put(pm.hash, v)
              knownPayloadCache.put(pm.hash, epi)
              payloadsResult.onNext(epi)
            case Left(err) => log.debug(err)
          }
          State.Idle(Some(ch))
        case other =>
          Option(addrToPK.get(pm.feeRecipient)) match {
            case Some(pk) =>
              if (pm.isSignatureValid(pk)) {
                pm.payloadInfo match {
                  case Right(epi) =>
                    lastPayloadMessages.put(pm.hash, v)
                    knownPayloadCache.put(pm.hash, epi)
                    payloadsResult.onNext(epi)
                  case Left(err) => log.debug(err)
                }
              } else {
                log.debug(s"Invalid signature for payload ${pm.hash}")
              }
            case None =>
              log.debug(s"Payload ${pm.hash} fee recipient ${pm.feeRecipient} is unknown miner")
          }
          other
      }
    }

  def loadPayload(req: BlockHash): CancelableFuture[ExecutionPayloadInfo] = {
    log.info(s"Loading payload $req")
    Option(knownPayloadCache.getIfPresent(req)) match {
      case None =>
        val p = CancelablePromise[ExecutionPayloadInfo]()
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
      case Some(epi) =>
        CancelablePromise.successful(epi).future
    }
  }

  def getPayloadStream: Observable[ExecutionPayloadInfo] = payloadsResult

  def broadcastSigned(payloadJson: JsObject, signer: PrivateKey): Either[String, PayloadMessage] = for {
    pm <- PayloadMessage.signed(payloadJson, signer)
    _ = log.debug(s"Broadcasting block ${pm.hash} payload")
    _ <- Try(allChannels.broadcast(pm)).toEither.leftMap(err => s"Failed to broadcast block ${pm.hash} payload: ${err.toString}")
  } yield pm

  override def broadcast(hash: BlockHash): Unit = {
    (for {
      payloadWithChannel <- Option(lastPayloadMessages.get(hash)).toRight(s"No prepared for broadcast payload $hash")
      _                  <- Either.cond(hash == payloadWithChannel.pm.hash, (), s"Payload for block $hash is not last received")
      _ = log.debug(s"Broadcasting block ${payloadWithChannel.pm.hash} payload")
      _ <- Try(allChannels.broadcast(payloadWithChannel.pm, Some(payloadWithChannel.ch))).toEither.leftMap(_.getMessage)
    } yield ()).fold(
      err => log.error(s"Failed to broadcast last received payload: $err"),
      identity
    )

    lastPayloadMessages.remove(hash)
  }

  def updateMinerPublicKeys(newKeys: Map[EthAddress, PublicKey]): Unit = {
    addrToPK.clear()
    addrToPK.putAll(newKeys.asJava)
  }

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
  sealed trait State

  object State {
    case class LoadingPayload(blockHash: BlockHash, nextAttempt: Cancelable, promise: CancelablePromise[ExecutionPayloadInfo]) extends State

    case class Idle(pinnedChannel: Option[Channel]) extends State
  }

}
