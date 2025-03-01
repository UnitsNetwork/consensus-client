resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.sbtPluginRepo("releases")
)

libraryDependencies ++= Seq(
  "org.web3j"          % "codegen"   % "4.9.8",
  "com.typesafe.play" %% "play-json" % "2.10.6"
)

Seq(
  "com.github.sbt"    % "sbt-native-packager" % "1.10.0",
  "com.github.sbt"    % "sbt-ci-release"      % "1.5.12",
  "se.marcuslonnberg" % "sbt-docker"          % "1.10.0"
).map(addSbtPlugin)
