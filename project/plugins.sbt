resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.sbtPluginRepo("releases")
)

libraryDependencies ++= Seq(
  "org.web3j"          % "codegen"       % "4.9.8",
  "org.playframework" %% "play-json"     % "3.0.6",
  "com.spotify"        % "docker-client" % "8.16.0"
)

Seq(
  "com.github.sbt" % "sbt-git"             % "3.0.0",
  "com.github.sbt" % "sbt-native-packager" % "1.13.0",
  "org.scalameta"  % "sbt-scalafmt"        % "2.6.2",
  "com.github.sbt" % "sbt2-compat"         % "0.2.0"
).map(addSbtPlugin)
