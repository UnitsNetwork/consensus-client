import com.github.sbt.git.SbtGit.git.gitCurrentBranch
import com.spotify.docker.client.DefaultDockerClient
import org.web3j.codegen.SolidityFunctionWrapperGenerator
import org.web3j.tx.Contract
import play.api.libs.json.Json
import sbt.Tests.Group

import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.sys.process.*
import scala.sys.props
import scala.util.control.NonFatal

description := "Consensus client integration tests"

libraryDependencies ++= Seq(
  "org.testcontainers" % "testcontainers" % "2.0.1",
  "org.web3j"          % "core"           % "4.9.8"
).map(_ % Test)

Test / sourceGenerators += Def.task {
  val generateSourcesFromContracts = Seq("Bridge", "StandardBridge", "ERC20", "TERC20")
  val contractSources              = baseDirectory.value / ".." / "contracts" / "eth"
  val compiledDir                  = contractSources / "target"
  // --silent to bypass garbage "Counting objects" git logs
  s"forge build --silent --config-path ${contractSources / "foundry.toml"} --contracts ${contractSources / "src"}" !

  generateSourcesFromContracts.foreach { contract =>
    val json    = Json.parse(new FileInputStream(compiledDir / s"$contract.sol" / s"$contract.json"))
    val abiFile = compiledDir / s"$contract.abi"
    val binFile = compiledDir / s"$contract.bin"

    IO.write(abiFile, Json.toBytes((json \ "abi").get))
    IO.write(binFile, (json \ "bytecode" \ "object").as[String])

    new SolidityFunctionWrapperGenerator(
      binFile,
      abiFile,
      (Test / sourceManaged).value,
      contract,
      "units.bridge",
      true,
      true,
      true,
      classOf[Contract],
      160,
      true
    ).generate()
  }

  generateSourcesFromContracts.map { contract =>
    (Test / sourceManaged).value / "units" / "bridge" / s"$contract.java"
  }
}

val logsDirectory = taskKey[File]("The directory for logs") // Task to evaluate and recreate the logs directory every time

Global / concurrentRestrictions := Seq(
  Tags.limit(
    Tags.ForkedTestGroup,
    Option(Integer.getInteger("cc.it.max-parallel-suites"))
      .getOrElse[Integer] {
        try {
          val docker = DefaultDockerClient.fromEnv().build()
          try {
            val dockerCpu: Int = docker.info().cpus()
            sLog.value.info(s"Docker CPU count: $dockerCpu")
            dockerCpu * 2
          } finally docker.close()
        } catch {
          case NonFatal(_) =>
            sLog.value.warn(s"Could not connect to Docker, is the daemon running?")
            sLog.value.info(s"System CPU count: ${EvaluateTask.SystemProcessors}")
            EvaluateTask.SystemProcessors
        }
      }
  )
)

val LogbackTestLevel = "logback.test.level"
inConfig(Test)(
  Seq(
    logsDirectory := {
      val runId: String = Option(System.getenv("RUN_ID")).getOrElse(DateTimeFormatter.ofPattern("MM-dd--HH_mm_ss").format(LocalDateTime.now))
      val r             = target.value / "test-logs" / runId
      r.mkdirs()
      r
    },
    javaOptions ++= Seq(
      s"-Dlogback.configurationFile=${(Test / resourceDirectory).value}/logback-test.xml", // Fixes a logback blaming for multiple configs
      s"-Dcc.it.configs.dir=${baseDirectory.value.getParent}/local-network/configs",
      s"-Dcc.it.docker.image=consensus-client:${gitCurrentBranch.value}",
      s"-Dcc.it.contracts.dir=${baseDirectory.value / ".." / "contracts" / "eth"}",
      s"-D$LogbackTestLevel=${props.getOrElse(LogbackTestLevel, "TRACE")}"
    ),
    testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-fFWD", ((Test / logsDirectory).value / "summary.log").toString),
    fork               := true,
    testForkedParallel := true,
    testGrouping := {
      val javaHomeVal    = (test / javaHome).value
      val baseLogDirVal  = (Test / logsDirectory).value
      val envVarsVal     = (Test / envVars).value
      val javaOptionsVal = (Test / javaOptions).value

      val tests = (Test / definedTests).value

      tests.zipWithIndex.map { case (suite, i) =>
        val suiteLogDir = baseLogDirVal / suite.name.replaceAll("""(\w)\w*\.""", "$1.") // foo.bar.Baz -> f.b.Baz
        Group(
          suite.name,
          Seq(suite),
          Tests.SubProcess(
            ForkOptions(
              javaHome = javaHomeVal,
              outputStrategy = (Test / outputStrategy).value,
              bootJars = Vector.empty[java.io.File],
              workingDirectory = Option((Test / baseDirectory).value),
              runJVMOptions = Vector(
                s"-Dcc.it.logs.dir=$suiteLogDir"
              ) ++ javaOptionsVal,
              connectInput = false,
              envVars = envVarsVal
            )
          )
        )
      }
    }
  )
)
