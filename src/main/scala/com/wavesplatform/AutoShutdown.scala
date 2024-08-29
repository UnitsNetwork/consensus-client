package com.wavesplatform

import scala.concurrent.Future

trait AutoShutdown {
  def shutdown(): Future[Unit]
}

object AutoShutdown {
  object Done extends AutoShutdown {
    override def shutdown(): Future[Unit] = Future.unit
  }
}
