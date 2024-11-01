package com.wavesplatform.api

import java.util.concurrent.ThreadLocalRandom

object LoggingUtil {
  def currRequestId: Int = ThreadLocalRandom.current().nextInt(10000, 100000)
}
