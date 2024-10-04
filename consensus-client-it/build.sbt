libraryDependencies ++= Seq(
  "org.testcontainers" % "testcontainers" % "1.20.2" % Test
)

Test / fork := true
Test / envVars ++= Map(
  "CONFIGS_DIR" -> s"${baseDirectory.value}/../local-network/configs",
  "LOGS_DIR"    -> s"${target.value}/test-logs"
)
