package units.docker

import com.google.common.io.Files as GFiles
import com.google.common.primitives.{Bytes, Ints}
import com.typesafe.config.ConfigFactory
import com.wavesplatform.account.{Address, SeedKeyPair}
import com.wavesplatform.api.NodeHttpApi
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.{GenesisBlockGenerator, crypto}
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network.NetworkImpl
import sttp.client3.{Identity, SttpBackend, UriContext}
import units.docker.WavesNodeContainer.*
import units.test.TestEnvironment.*

import java.io.{File, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.MapHasAsJava
import scala.jdk.DurationConverters.JavaDurationOps

class WavesNodeContainer(
    network: NetworkImpl,
    number: Int,
    ip: String,
    baseSeed: String,
    chainContractAddress: Address,
    ecEngineApiUrl: String,
    genesisConfigPath: Path
)(implicit httpClientBackend: SttpBackend[Identity, Any])
    extends BaseContainer(s"waves-node-$number") {
  private val logFile = new File(s"$DefaultLogsDir/waves-$number.log")
  GFiles.touch(logFile)

  protected override val container = new GenericContainer(DockerImages.WavesNode)
    .withNetwork(network)
    .withExposedPorts(ApiPort, NetworkPort)
    .withEnv(
      Map(
        "NODE_NUMBER"       -> s"$number",
        "WAVES_WALLET_SEED" -> Base58.encode(baseSeed.getBytes(StandardCharsets.UTF_8)),
        "JAVA_OPTS" -> List(
          "-Dwaves.miner.quorum=0",
          s"-Dunits.defaults.chain-contract=$chainContractAddress",
          s"-Dunits.defaults.execution-client-address=$ecEngineApiUrl",
          s"-Dunits.defaults.jwt-secret-file=/etc/secrets/jwtsecret.hex",
          "-Dlogback.file.level=TRACE",
          "-Dfile.encoding=UTF-8",
          "-XX:+IgnoreUnrecognizedVMOptions",
          "-XX:UseSVE=0" // https://github.com/adoptium/adoptium-support/issues/1223
        ).mkString(" "),
        "WAVES_LOG_LEVEL" -> "TRACE", // STDOUT logs
        "WAVES_HEAP_SIZE" -> "1g"
      ).asJava
    )
    .withFileSystemBind(s"$ConfigsDir/wavesnode", "/etc/waves", BindMode.READ_ONLY)
    .withFileSystemBind(s"$ConfigsDir/ec-common", "/etc/secrets", BindMode.READ_ONLY)
    .withFileSystemBind(s"$genesisConfigPath", "/etc/it/genesis.conf", BindMode.READ_ONLY)
    .withFileSystemBind(s"$logFile", "/var/log/waves/waves.log", BindMode.READ_WRITE)
    .withCreateContainerCmdModifier { cmd =>
      cmd
        .withName(s"${network.getName}-$hostName")
        .withHostName(hostName)
        .withIpv4Address(ip)
        .withStopTimeout(5) // Otherwise we don't have logs in the end
    }

  lazy val apiPort = container.getMappedPort(ApiPort)
  lazy val networkPort = container.getMappedPort(NetworkPort)

  lazy val api = new NodeHttpApi(uri"http://${container.getHost}:$apiPort", httpClientBackend)

  override def logPorts(): Unit = log.debug(s"External host: ${container.getHost}, api: $apiPort, network: $networkPort")
}

object WavesNodeContainer {
  val ApiPort = 6869
  val NetworkPort = 6865

  val GenesisTemplateFile = new File(s"$ConfigsDir/wavesnode/genesis-template.conf")
  val GenesisTemplate     = ConfigFactory.parseFile(GenesisTemplateFile)
  val MaxBlockDelay       = GenesisTemplate.getDuration("genesis-generator.average-block-delay").toScala * 2

  def mkKeyPair(seed: String, nonce: Int): SeedKeyPair =
    SeedKeyPair(crypto.secureHash(Bytes.concat(Ints.toByteArray(nonce), seed.getBytes(StandardCharsets.UTF_8))))

  def generateWavesGenesisConfig(): Path = {
    val templateFile = ConfigsDir.resolve("wavesnode/genesis-template.conf").toAbsolutePath

    val origConfig = ConfigFactory.parseFile(templateFile.toFile)
    val gap        = 25.seconds // To force node mining at start, otherwise it schedules
    val overrides = ConfigFactory.parseString(
      s"""genesis-generator {
         |  timestamp = ${System.currentTimeMillis() - gap.toMillis}
         |}""".stripMargin
    )

    val genesisSettings = GenesisBlockGenerator.parseSettings(overrides.withFallback(origConfig))

    val origOut = System.out
    System.setOut(new PrintStream({ (_: Int) => })) // We don't use System.out in tests, so it should not be an issue
    val config = GenesisBlockGenerator.createConfig(genesisSettings)
    System.setOut(origOut)

    val dest = DefaultLogsDir.resolve("genesis.conf").toAbsolutePath
    Files.writeString(dest, config)
    dest
  }
}
