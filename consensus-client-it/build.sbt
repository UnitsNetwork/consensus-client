import com.github.sbt.git.SbtGit.git.gitCurrentBranch
import org.web3j.codegen.SolidityFunctionWrapperGenerator
import org.web3j.tx.Contract
import play.api.libs.json.Json
import sbt.Tests.Group

import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.sys.process.*

description := "Consensus client integration tests"

libraryDependencies ++= Seq(
  "org.testcontainers" % "testcontainers" % "1.20.4",
  "org.web3j"          % "core"           % "4.9.8"
).map(_ % Test)

Test / sourceGenerators += Def.task {
  val contracts       = Seq("Bridge", "StandardBridge", "TERC20", "UnitsMintableERC20")
  val contractSources = baseDirectory.value / ".." / "contracts" / "eth"
  val compiledDir     = contractSources / "target"
  // --silent to bypass garbage "Counting objects" git logs
  s"forge build --silent --config-path ${contractSources / "foundry.toml"} ${contractSources / "src"} ${contractSources / "utils" / "TERC20.sol"} ${contractSources / "src" / "UnitsMintableERC20.sol"}" !

  contracts.foreach { contract =>
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

  contracts.map { contract =>
    (Test / sourceManaged).value / "units" / "bridge" / s"$contract.java"
  }
}

val logsDirectory = taskKey[File]("The directory for logs") // Task to evaluate and recreate the logs directory every time

Global / concurrentRestrictions := {
  val threadNumber = Option(System.getenv("SBT_IT_TEST_THREADS")).fold(1)(_.toInt)
  Seq(Tags.limit(Tags.ForkedTestGroup, threadNumber))
}

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
      s"-Dcc.it.contracts.dir=${baseDirectory.value / ".." / "contracts" / "eth"}"
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
