package units

import com.wavesplatform.common.utils.EitherExt2
import units.client.engine.model.BlockNumber
import units.docker.{EcContainer, Networks, WavesNodeContainer}

trait TwoNodesTestSuite extends BaseItTestSuite {
  protected lazy val ec1: EcContainer = new EcContainer(
    network = network,
    number = 1,
    ip = Networks.ipForNode(2) // ipForNode(1) is assigned to Ryuk
  )

  protected lazy val ec2: EcContainer = new EcContainer(
    network = network,
    number = 2,
    ip = Networks.ipForNode(3)
  )

  protected lazy val waves1: WavesNodeContainer = new WavesNodeContainer(
    network = network,
    number = 1,
    ip = Networks.ipForNode(4),
    baseSeed = "devnet-1",
    clMinerKeyPair = mkKeyPair("devnet-1", 0),
    chainContractAddress = chainContractAddress,
    ecEngineApiUrl = s"http://${ec1.hostName}:${EcContainer.EnginePort}",
    genesisConfigPath = wavesGenesisConfigPath
  )

  protected lazy val waves2: WavesNodeContainer = new WavesNodeContainer(
    network = network,
    number = 2,
    ip = Networks.ipForNode(5),
    baseSeed = "devnet-2",
    clMinerKeyPair = mkKeyPair("devnet-2", 0),
    chainContractAddress = chainContractAddress,
    ecEngineApiUrl = s"http://${ec2.hostName}:${EcContainer.EnginePort}",
    genesisConfigPath = wavesGenesisConfigPath
  )

  override protected def startNodes(): Unit = {
    ec1.start()
    ec1.logPorts()

    ec2.start()
    ec2.logPorts()

    waves1.start()
    waves1.logPorts()

    waves2.start()
    waves2.logPorts()

    waves1.waitReady()
    waves2.waitReady()

    waves1.api.waitForConnectedPeers(1)

    // HACK: EC nodes won't connect each other
    ec1.waitReady()
    ec2.waitReady()
    for {
      peer <- EcContainer.peersVal
      ec   <- List(ec1, ec2)
    } {
      log.debug(s"Add $peer to ${ec.hostName}")
      ec.web3j.adminAddPeer(peer).send()
    }
  }

  override protected def stopNodes(): Unit = {
    waves1.stop()
    waves2.stop()
    ec1.stop()
    ec2.stop()
  }

  override protected def setupNetwork(): Unit = {
    log.info("Set script")
    waves1.api.broadcastAndWait(chainContract.setScript())

    log.info("Setup chain contract")
    val genesisBlock = ec1.engineApi.getBlockByNumber(BlockNumber.Number(0)).explicitGet().getOrElse(fail("No EL genesis block"))
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

    log.info("Waves miner #1 join")
    waves1.api.broadcastAndWait(
      chainContract.join(
        minerAccount = miner11Account,
        elRewardAddress = miner11RewardAddress
      )
    )

    log.info("Waves miner #2 join")
    val joinMiner2Result = waves1.api.broadcastAndWait(
      chainContract.join(
        minerAccount = miner21Account,
        elRewardAddress = miner21RewardAddress
      )
    )

    val epoch1Number = joinMiner2Result.height + 1
    log.info(s"Wait for #$epoch1Number epoch")
    waves1.api.waitForHeight(epoch1Number)
  }

  override protected def print(text: String): Unit = {
    super.print(text)
    waves1.api.print(text)
    waves2.api.print(text)
  }
}
