import com.github.sbt.git.SbtGit.GitKeys.gitCurrentBranch

import scala.sys.process.{Process, ProcessLogger}

enablePlugins(UniversalDeployPlugin, GitVersioning, VersionObject)

git.useGitDescribe       := true
git.baseVersion          := "1.4.0"
git.uncommittedSignifier := Some("DIRTY")

inScope(Global)(
  Seq(
    onChangedBuildSource := ReloadOnSourceChanges,
    scalaVersion         := "3.7.4",
    organization         := "network.units",
    organizationName     := "Units Network",
    resolvers ++= Seq(Resolver.sonatypeCentralSnapshots, Resolver.mavenLocal),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      "-Wshadow:all",
      "-Wunused:all",
      "-explain-cyclic",
      "-Wimplausible-patterns",
      "-Wsafe-init",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-language:postfixOps"
    )
  )
)

name       := "consensus-client"
maintainer := "Units Network Team"

// These overrides are needed so that there are no different versions of the same component on the classpath when the extension is installed
dependencyOverrides ++= Seq(
  "org.playframework"   %% "play-json"           % "3.0.6",
  "com.squareup.okhttp3" % "okhttp"              % "4.12.0",
  "com.squareup.okhttp3" % "logging-interceptor" % "4.12.0",
  "com.squareup.okio"    % "okio"                % "3.6.0",
  "com.squareup.okio"    % "okio-jvm"            % "3.16.4",
  "org.reactivestreams"  % "reactive-streams"    % "1.0.4",
  "org.jetbrains.kotlin" % "kotlin-stdlib"       % "2.2.20",
  "org.jetbrains.kotlin" % "kotlin-stdlib-jdk7"  % "1.8.21",
  "org.jetbrains.kotlin" % "kotlin-stdlib-jdk8"  % "1.8.21"
)

libraryDependencies ++= {
  val node        = "1.6.1"
  val sttpVersion = "3.11.0"
  Seq(
    "com.wavesplatform"              % "node-testkit"  % node % Test,
    "com.wavesplatform"              % "node"          % node % Provided,
    "com.softwaremill.sttp.client3" %% "core"          % sttpVersion,
    "com.softwaremill.sttp.client3" %% "play-json"     % sttpVersion,
    "com.github.jwt-scala"          %% "jwt-play-json" % "11.0.3",
    ("org.web3j"                     % "core"          % "4.9.8").excludeAll(
      ExclusionRule("org.slf4j", "slf4j-api"),
      ExclusionRule("org.bouncycastle", "bcprov-jdk15on")
    )
  )
}

Compile / packageDoc / publishArtifact := false

def makeJarName(
    org: String,
    name: String,
    revision: String,
    artifactName: String,
    artifactClassifier: Option[String]
): String =
  org + "." +
    name + "-" +
    Option(artifactName.replace(name, "")).filterNot(_.isEmpty).map(_ + "-").getOrElse("") +
    revision +
    artifactClassifier.filterNot(_.isEmpty).map("-" + _).getOrElse("") +
    ".jar"

def getJarFullFilename(dep: Attributed[File]): String = {
  val filename: Option[String] = for {
    module   <- dep.metadata.get(AttributeKey[ModuleID]("moduleID"))
    artifact <- dep.metadata.get(AttributeKey[Artifact]("artifact"))
  } yield makeJarName(module.organization, module.name, module.revision, artifact.name, artifact.classifier)
  filename.getOrElse(dep.data.getName)
}

def universalDepMappings(deps: Seq[Attributed[File]]): Seq[(File, String)] =
  for {
    dep <- deps
  } yield dep.data -> ("lib/" + getJarFullFilename(dep))

Universal / mappings += {
  val jar = (Compile / packageBin).value
  val id  = projectID.value
  val art = (Compile / packageBin / artifact).value
  jar -> ("lib/" + makeJarName(id.organization, id.name, id.revision, art.name, art.classifier))
}
Universal / mappings ++= universalDepMappings((Runtime / dependencyClasspath).value.filterNot { p =>
  p.get(AttributeKey[ModuleID]("moduleID")).exists { m =>
    m.organization == "org.scala-lang" ||
    m.organization.startsWith("com.fasterxml.jackson")
  }
})

lazy val buildTarballsForDocker = taskKey[Unit]("Package consensus-client tarball and copy it to docker/target")
buildTarballsForDocker := {
  IO.copyFile(
    (Universal / packageZipTarball).value,
    baseDirectory.value / "docker" / "target" / "consensus-client.tgz"
  )
}

val docker = taskKey[Unit]("Build docker image for integration tests")
docker := {
  val log = streams.value.log

  val cwd = baseDirectory.value / "docker"

  val cmd = Seq("docker", "build", "-t", "consensus-client:local", "-t", s"consensus-client:${gitCurrentBranch.value}", ".")
  log.info(s"Running `${cmd.mkString(" ")}` from $cwd")

  val processLogger = ProcessLogger(
    (out: String) => log.info(out),
    (err: String) => log.info(err) // Redirect STDERR to info
  )

  val exit = Process(cmd, cwd).!(processLogger)
  if (exit != 0) sys.error(s"Docker build failed with exit code $exit")
}

docker := docker.dependsOn(LocalRootProject / buildTarballsForDocker).value

lazy val `consensus-client` = project
  .in(file("."))
  .settings(
    Test / resources += baseDirectory.value / "contracts" / "waves" / "src" / "main.ride"
  )

lazy val `consensus-client-it` = project
  .dependsOn(
    `consensus-client` % "compile;test->test"
  )
