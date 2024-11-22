import com.github.sbt.git.SbtGit.git.gitCurrentBranch
import sbt.Tests.Group

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

description := "Consensus client integration tests"

libraryDependencies ++= Seq(
  "org.testcontainers" % "testcontainers" % "1.20.3",
  "org.web3j"          % "core"           % "4.9.8"
).map(_ % Test)

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
      s"-Dcc.it.docker.image=consensus-client:${gitCurrentBranch.value}"
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
