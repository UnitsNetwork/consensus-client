import com.github.sbt.git.SbtGit.git.gitCurrentBranch
import sbt.Tests.Group

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

description := "Consensus client integration tests"

libraryDependencies ++= Seq(
  "org.testcontainers" % "testcontainers" % "1.20.2"
).map(_ % Test)

val logsDirectory = taskKey[File]("The directory for logs") // Evaluates every time, so it recreates the logs directory

inConfig(Test)(
  Seq(
    logsDirectory := {
      val runId: String = Option(System.getenv("RUN_ID")).getOrElse(DateTimeFormatter.ofPattern("MM-dd--HH_mm_ss").format(LocalDateTime.now))
      val r             = target.value / "test-logs" / runId
      r.mkdirs()
      r
    },
    fork := true,
    javaOptions ++= Seq(
      s"-Dlogback.configurationFile=${(Test / resourceDirectory).value}/logback-test.xml", // Fixes a logback blaming for multiple configs
      s"-Dcc.it.configs.dir=${baseDirectory.value.getParent}/local-network/configs",
      s"-Dcc.it.docker.image=consensus-client:${gitCurrentBranch.value}"
    ),
    testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-fFWD", ((Test / logsDirectory).value / "summary.log").toString),
    parallelExecution := true,
    testGrouping := {
      val PORTS_PER_TEST     = 50
      val DEFAULT_PORT_RANGE = (10000, 32000)

      val javaHomeValue     = (test / javaHome).value
      val logDirectoryValue = (Test / logsDirectory).value
      val envVarsValue      = (Test / envVars).value
      val javaOptionsValue  = (Test / javaOptions).value

      val tests = (Test / definedTests).value

      tests.zipWithIndex.map { case (suite, i) =>
        Group(
          suite.name,
          Seq(suite),
          Tests.SubProcess(
            ForkOptions(
              javaHome = javaHomeValue,
              outputStrategy = (Test / outputStrategy).value,
              bootJars = Vector.empty[java.io.File],
              workingDirectory = Option((Test / baseDirectory).value),
              runJVMOptions = Vector(
                s"-Dcc.it.logs.dir=${logDirectoryValue / suite.name.replaceAll("""(\w)\w*\.""", "$1.")}" // foo.bar.Baz -> f.b.Baz
              ) ++ javaOptionsValue,
              connectInput = false,
              envVars = envVarsValue
            )
          )
        )
      }
    }
  )
)
