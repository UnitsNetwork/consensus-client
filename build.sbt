enablePlugins(UniversalDeployPlugin, JavaAppPackaging, GitVersioning)

git.useGitDescribe := true
git.baseVersion := "1.0.0"
git.uncommittedSignifier := Some("DIRTY")

scalaVersion := "2.13.14"
organization := "network.units"
organizationName := "Units Network"
name := "consensus-client"
resolvers ++= Resolver.sonatypeOssRepos("releases") ++ Resolver.sonatypeOssRepos("snapshots") ++ Seq(Resolver.mavenLocal)
libraryDependencies ++= Seq(
  "com.wavesplatform" % "node" % "1.5.7" % "provided",
  "com.softwaremill.sttp.client3" % "core_2.13" % "3.9.8",
  "com.softwaremill.sttp.client3" %% "play-json" % "3.9.8",
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

lazy val buildTarballsForDocker = taskKey[Unit]("Package node and grpc-server tarballs and copy them to docker/target")
buildTarballsForDocker := {
  IO.copyFile(
    (Universal / packageZipTarball).value,
    baseDirectory.value / "docker" / "target" / "consensus-client.tgz"
  )
}
