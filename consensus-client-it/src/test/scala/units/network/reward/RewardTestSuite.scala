package units.network.reward

import units.network.BaseItTestSuite

class RewardTestSuite extends BaseItTestSuite {
  "Test" in {
    Thread.sleep(10000)
    log.debug(s"Ports: ec engine = ${ec1.enginePort()}, ec rpc = ${ec1.rpcPort()}, waves api = ${waves1.apiPort()}")
  }
}
