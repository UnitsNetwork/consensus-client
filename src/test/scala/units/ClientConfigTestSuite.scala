package units

import com.typesafe.config.ConfigFactory
import com.wavesplatform.account.PrivateKey
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.test.FlatSpec
import com.wavesplatform.transaction.TxHelpers
import pureconfig.ConfigSource

import scala.concurrent.duration.*

class ClientConfigTestSuite extends FlatSpec {
  private val stubNetworkSettingsString =
    s"""
       |{
       |  bind-address: "127.0.0.1"
       |  port: 6868
       |  node-name: "default-node-name"
       |  declared-address: "127.0.0.1:6868"
       |  nonce: 0
       |  known-peers = ["8.8.8.8:6868", "4.4.8.8:6868"]
       |  local-only: no
       |  peers-data-residence-time: 1d
       |  black-list-residence-time: 10m
       |  break-idle-connections-timeout: 53s
       |  max-inbound-connections: 30
       |  max-outbound-connections = 20
       |  max-single-host-connections = 2
       |  connection-timeout: 30s
       |  max-unverified-peers: 0
       |  enable-peers-exchange: true
       |  enable-blacklisting: true
       |  peers-broadcast-interval: 2m
       |  handshake-timeout: 10s
       |  black-list-threshold: 50
       |  unrequested-packets-threshold: 100
       |  suspension-residence-time: 5s
       |  received-txs-cache-timeout: 5s
       |  upnp {
       |    enable: yes
       |    gateway-timeout: 10s
       |    discover-timeout: 10s
       |  }
       |  traffic-logger {
       |    ignore-tx-messages = [28]
       |    ignore-rx-messages = [23]
       |  }
       |}
       |
       |""".stripMargin

  "ClientConfig" should "read config with multiple private keys" in {
    val configString =
      s"""
         |units {
         |  defaults {
         |    chain-contract = "3FXDd4LoxxqVLfMk8M25f8CQvfCtGMyiXV1"
         |    execution-client-address = "http://ec-1:8551"
         |
         |    api-request-retries = 2
         |    api-request-retry-wait-time = 2s
         |
         |    block-delay = 6s
         |    first-block-min-delay = 1s
         |    block-sync-request-timeout = 500ms
         |
         |    network = $stubNetworkSettingsString
         |
         |    mining-enable = true
         |    private-keys = [${TxHelpers.signer(0).privateKey}, ${TxHelpers.signer(1).privateKey}]
         |  }
         |  chains = []
         |}
         |""".stripMargin
    val config = ConfigFactory
      .parseString(configString)
      .resolve()

    val clientConfig = ConfigSource.fromConfig(config).at("units.defaults").loadOrThrow[ClientConfig]

    clientConfig.chainContract shouldBe "3FXDd4LoxxqVLfMk8M25f8CQvfCtGMyiXV1"
    clientConfig.executionClientAddress shouldBe "http://ec-1:8551"
    clientConfig.apiRequestRetries shouldBe 2
    clientConfig.apiRequestRetryWaitTime shouldBe 2.seconds
    clientConfig.blockDelay shouldBe 6.seconds
    clientConfig.firstBlockMinDelay shouldBe 1.second
    clientConfig.blockSyncRequestTimeout shouldBe 500.millis
    clientConfig.miningEnable shouldBe true
    clientConfig.privateKeys shouldBe Seq(
      PrivateKey(ByteStr.decodeBase58("FZ97ouxTGpNnmyyfSBxgC2FGHTpvo7mM7LWoMut6gEYx").get),
      PrivateKey(ByteStr.decodeBase58("Cr91PMiwAcagptwmeVkDC6Cpeu2XgEbxD4A8oTZtwK3r").get)
    )
  }
  it should "read config with no private keys" in {
    val configString =
      s"""
         |units {
         |  defaults {
         |    chain-contract = "3FXDd4LoxxqVLfMk8M25f8CQvfCtGMyiXV1"
         |    execution-client-address = "http://ec-1:8551"
         |
         |    api-request-retries = 2
         |    api-request-retry-wait-time = 2s
         |
         |    block-delay = 6s
         |    first-block-min-delay = 1s
         |    block-sync-request-timeout = 500ms
         |
         |    network = $stubNetworkSettingsString
         |
         |    mining-enable = true
         |    # private-keys = ???
         |  }
         |  chains = []
         |}
         |""".stripMargin
    val config = ConfigFactory
      .parseString(configString)
      .resolve()

    val clientConfig = ConfigSource.fromConfig(config).at("units.defaults").loadOrThrow[ClientConfig]

    clientConfig.chainContract shouldBe "3FXDd4LoxxqVLfMk8M25f8CQvfCtGMyiXV1"
    clientConfig.executionClientAddress shouldBe "http://ec-1:8551"
    clientConfig.apiRequestRetries shouldBe 2
    clientConfig.apiRequestRetryWaitTime shouldBe 2.seconds
    clientConfig.blockDelay shouldBe 6.seconds
    clientConfig.blockSyncRequestTimeout shouldBe 500.millis
    clientConfig.miningEnable shouldBe true
    clientConfig.privateKeys shouldBe Seq.empty
  }
}
