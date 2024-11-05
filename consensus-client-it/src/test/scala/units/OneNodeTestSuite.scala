package units

import com.wavesplatform.common.utils.EitherExt2
import units.client.engine.model.BlockNumber
import units.docker.{EcContainer, Networks, WavesNodeContainer}

trait OneNodeTestSuite extends BaseItTestSuite {
  protected lazy val ec1: EcContainer = new EcContainer(
    network = network,
    number = 1,
    ip = Networks.ipForNode(2) // ipForNode(1) is assigned to Ryuk
  )

  protected lazy val waves1: WavesNodeContainer = new WavesNodeContainer(
    network = network,
    number = 1,
    ip = Networks.ipForNode(3),
    baseSeed = "devnet-1",
    clMinerKeyPair = mkKeyPair("devnet-1", 0),
    chainContractAddress = chainContractAddress,
    ecEngineApiUrl = s"http://${ec1.hostName}:${EcContainer.EnginePort}",
    genesisConfigPath = wavesGenesisConfigPath
  )

  override protected def startNodes(): Unit = {
    ec1.start()
    ec1.logPorts()

    waves1.start()
    waves1.waitReady()
    waves1.logPorts()
  }

  override protected def stopNodes(): Unit = {
    waves1.stop()
    ec1.stop()
  }

  override protected def setupNetwork(): Unit = {
    log.info("Set script")
    waves1.api.broadcastAndWait(chainContract.setScript())

    log.info("Setup chain contract")
    val genesisBlock = ec1.engineApi.getBlockByNumber(BlockNumber.Number(0)).explicitGet().getOrElse(failRetry("No EL genesis block"))
    waves1.api.broadcastAndWait(
      chainContract.setup(
        genesisBlock = genesisBlock,
        elMinerReward = rewardAmount.amount.longValue(),
        daoAddress = None,
        daoReward = 0,
        invoker = chainContractAccount
      )
    )

    log.info(s"Token id: ${waves1.chainContract.token}")

    joinMiners()
  }

  protected def joinMiners(): Unit

  override protected def print(text: String): Unit = {
    super.print(text)
    waves1.api.print(text)
  }
}

object OneNodeTestSuite {
  trait OneMiner { this: OneNodeTestSuite =>
    override protected def joinMiners(): Unit = {
      log.info("EL miner #1 join")
      val joinMiner1Result = waves1.api.broadcastAndWait(
        chainContract.join(
          minerAccount = miner11Account,
          elRewardAddress = miner11RewardAddress
        )
      )

      val epoch1Number = joinMiner1Result.height + 1
      log.info(s"Wait for #$epoch1Number epoch")
      waves1.api.waitForHeight(epoch1Number)
    }
  }

  trait TwoMiners { this: OneNodeTestSuite =>
    override protected def joinMiners(): Unit = {
      waves1.api.createWalletAddress() // Init miner2Account

      log.info("EL miner #1 join")
      waves1.api.broadcastAndWait(
        chainContract.join(
          minerAccount = miner11Account,
          elRewardAddress = miner11RewardAddress
        )
      )

      log.info("EL miner #2 join")
      val joinMiner2Result = waves1.api.broadcastAndWait(
        chainContract.join(
          minerAccount = miner12Account,
          elRewardAddress = miner12RewardAddress
        )
      )

      val epoch1Number = joinMiner2Result.height + 1
      log.info(s"Wait for #$epoch1Number epoch")
      waves1.api.waitForHeight(epoch1Number)
    }
  }
}
