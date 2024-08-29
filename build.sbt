scalaVersion := "2.13.12"
organization := "network.units"
organizationName := "Units Network"
name := "consensus-client"
resolvers ++= Resolver.sonatypeOssRepos("releases") ++ Resolver.sonatypeOssRepos("snapshots") ++ Seq(Resolver.mavenLocal)
libraryDependencies ++= Seq(
  "com.wavesplatform" % "node" % "1.5.7",
  "com.softwaremill.sttp.client3" % "core_2.13" % "3.9.2",
  "com.softwaremill.sttp.client3" %% "monix" % "3.9.2",
  "com.softwaremill.sttp.client3" %% "play-json" % "3.9.2",
  "com.github.jwt-scala" %% "jwt-play-json" % "10.0.1"
)
scalacOptions ++= Seq(
  "-Xsource:3",
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:postfixOps",
  "-Ywarn-unused:-implicits",
  "-Xlint"
)
