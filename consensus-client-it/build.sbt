libraryDependencies ++= Seq(
  "org.testcontainers" % "testcontainers" % "1.20.2" % Test
)

Test / fork := true
Test / javaOptions ++= Seq(
  s"-Dlogback.configurationFile=${(Test / resourceDirectory).value}/logback-test.xml" // Fixes a logback blaming for multiple configs
)
Test / envVars ++= Map(
  "CONFIGS_DIR" -> s"${baseDirectory.value}/../local-network/configs",
  "LOGS_DIR"    -> s"${target.value}/test-logs"
)
