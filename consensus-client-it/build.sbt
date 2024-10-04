import com.github.sbt.git.SbtGit.git.gitCurrentBranch

description := "Consensus client integration tests"

libraryDependencies ++= Seq(
  "org.testcontainers" % "testcontainers" % "1.20.2" % Test
)

val logsDirectory = taskKey[File]("The directory for logs") // Evaluates every time, so it recreates the logs directory

inConfig(Test)(
  Seq(
    logsDirectory := {
      val r = target.value / "test-logs"
      r.mkdirs()
      r
    },
    fork := true,
    javaOptions ++= Seq(
      s"-Dlogback.configurationFile=${(Test / resourceDirectory).value}/logback-test.xml", // Fixes a logback blaming for multiple configs
      s"-Dcc.it.configs.dir=${baseDirectory.value.getParent}/local-network/configs",
      s"-Dcc.it.logs.dir=${(Test / logsDirectory).value}",
      s"-Dcc.it.docker.image=unitsnetwork/consensus-client:${gitCurrentBranch.value}"
    ),
    testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-fFWD", ((Test / logsDirectory).value / "summary.log").toString)
  )
)
