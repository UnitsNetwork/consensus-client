package units

import monix.execution.schedulers.ExecutorScheduler
import monix.execution.{Cancelable, Features, UncaughtExceptionReporter, ExecutionModel as ExecModel}

import java.util.concurrent.*
import scala.concurrent.duration.TimeUnit

abstract class AdaptedThreadPoolExecutor(corePoolSize: Int, factory: ThreadFactory) extends ScheduledThreadPoolExecutor(corePoolSize, factory) {
  def reportFailure(t: Throwable): Unit

  override def afterExecute(r: Runnable, t: Throwable): Unit = {
    super.afterExecute(r, t)
    var exception: Throwable = t

    if ((exception eq null) && r.isInstanceOf[Future[?]]) {
      try {
        val future = r.asInstanceOf[Future[?]]
        if (future.isDone) future.get()
      } catch {
        case ex: ExecutionException =>
          exception = ex.getCause
        case _: InterruptedException =>
          // ignore/reset
          Thread.currentThread().interrupt()
        case _: CancellationException =>
          () // ignore
      }
    }

    if (exception ne null) reportFailure(exception)
  }
}

class NonInterruptingScheduledExecutor(
    s: ScheduledExecutorService,
    r: UncaughtExceptionReporter,
    override val executionModel: ExecModel,
    override val features: Features
) extends ExecutorScheduler(s, r) {
  override def executor: ScheduledExecutorService = s

  def scheduleOnce(initialDelay: Long, unit: TimeUnit, r: Runnable): Cancelable = {
    if (initialDelay <= 0) {
      execute(r)
      Cancelable.empty
    } else {
      val task = s.schedule(r, initialDelay, unit)
      Cancelable(() => { task.cancel(false); () })
    }
  }

  override def scheduleWithFixedDelay(initialDelay: Long, delay: Long, unit: TimeUnit, r: Runnable): Cancelable = {
    val task = s.scheduleWithFixedDelay(r, initialDelay, delay, unit)
    Cancelable(() => { task.cancel(false); () })
  }

  override def scheduleAtFixedRate(initialDelay: Long, period: Long, unit: TimeUnit, r: Runnable): Cancelable = {
    val task = s.scheduleAtFixedRate(r, initialDelay, period, unit)
    Cancelable(() => { task.cancel(false); () })
  }
}

object NonInterruptingScheduledExecutor {
  def apply(name: String, reporter: UncaughtExceptionReporter): NonInterruptingScheduledExecutor = {
    val atpe = new AdaptedThreadPoolExecutor(
      1,
      (r: Runnable) => {
        val t = new Thread(r)
        t.setDaemon(true)
        t.setName(name)
        t.setUncaughtExceptionHandler(reporter.asJava)
        t
      }
    ) {
      override def reportFailure(t: Throwable): Unit = reporter.reportFailure(t)
    }
    new NonInterruptingScheduledExecutor(atpe, reporter, ExecModel.AlwaysAsyncExecution, Features.empty)
  }
}
