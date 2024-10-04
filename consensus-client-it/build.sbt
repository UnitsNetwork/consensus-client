libraryDependencies ++= Seq(
  "org.testcontainers" % "testcontainers" % "1.20.2" % Test
)

val logsDirectory = settingKey[File]("The directory for logs")

inConfig(Test)(
  Seq(
    logsDirectory := {
      val r = target.value / "test-logs"
      r.mkdirs()
      r
    },
    fork          := true,
    javaOptions ++= Seq(
      s"-Dlogback.configurationFile=${(Test / resourceDirectory).value}/logback-test.xml", // Fixes a logback blaming for multiple configs
      s"-Dcc.it.configs.dir=${baseDirectory.value.getParent}/local-network/configs",
      s"-Dcc.it.logs.dir=${logsDirectory.value}"
    ),
    testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-fFWD", (logsDirectory.value / "summary.log").toString)
  )
)
