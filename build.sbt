import com.github.sbt.git.SbtGit.GitKeys.gitCurrentBranch
import sbtcompat.PluginCompat.{FileRef, parseArtifactStrAttribute, parseModuleIDStrAttribute}

import scala.sys.process.{Process, ProcessLogger}

inScope(Global)(
  Seq(
    onChangedBuildSource := ReloadOnSourceChanges,
    scalaVersion         := "3.10.1",
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

// Keep these in sync with the versions Waves Node pins in its project/Dependencies.scala
def nettyModule(module: String): ModuleID = "io.netty" % s"netty-$module" % "4.2.20.Final"

def jacksonModule(group: String, module: String): ModuleID = s"com.fasterxml.jackson.$group" % s"jackson-$module" % "2.21.8"

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

def getJarFullFilename(dep: Attributed[FileRef]): String = {
  val filename: Option[String] = for {
    module   <- dep.metadata.get(moduleIDStr).map(parseModuleIDStrAttribute)
    artifact <- dep.metadata.get(artifactStr).map(parseArtifactStrAttribute)
  } yield makeJarName(module.organization, module.name, module.revision, artifact.name, artifact.classifier)
  filename.getOrElse(dep.data.name)
}

def universalDepMappings(deps: Seq[Attributed[FileRef]]): Seq[(FileRef, String)] =
  for {
    dep <- deps
  } yield dep.data -> ("lib/" + getJarFullFilename(dep))

lazy val buildTarballsForDocker = taskKey[Unit]("Package consensus-client tarball and copy it to docker/target")
lazy val docker                 = taskKey[Unit]("Build docker image for integration tests")

// sbt 2 applies bare settings in build.sbt to every subproject, so the root project's own settings live here
lazy val `consensus-client` = project
  .in(file("."))
  .enablePlugins(UniversalDeployPlugin, GitVersioning, VersionObject)
  .settings(
    git.useGitDescribe       := true,
    git.baseVersion          := "1.4.4",
    git.uncommittedSignifier := Some("DIRTY"),
    name                     := "consensus-client",
    maintainer               := "Units Network Team",
    // These overrides are needed so that there are no different versions of the same component on the classpath when the extension is installed
    dependencyOverrides ++= Seq(
      "org.playframework"        %% "play-json"           % "3.0.6",
      "com.squareup.okhttp3"      % "okhttp"              % "4.12.0",
      "com.squareup.okhttp3"      % "logging-interceptor" % "4.12.0",
      "com.squareup.okio"         % "okio"                % "3.6.0",
      "com.squareup.okio"         % "okio-jvm"            % "3.18.2",
      "org.apache.httpcomponents" % "httpclient"          % "4.5.14",
      "org.reactivestreams"       % "reactive-streams"    % "1.0.4",
      "org.jetbrains.kotlin"      % "kotlin-stdlib"       % "2.1.21",
      "org.jetbrains.kotlin"      % "kotlin-stdlib-jdk7"  % "1.8.21",
      "org.jetbrains.kotlin"      % "kotlin-stdlib-jdk8"  % "1.8.21",
      "org.bouncycastle"          % "bcprov-jdk18on"      % "1.87",
      "com.google.code.gson"      % "gson"                % "2.15.0",
      "org.slf4j"                 % "slf4j-api"           % "2.0.20",
      "org.msgpack"               % "msgpack-core"        % "0.9.12",
      jacksonModule("core", "core"),
      jacksonModule("core", "databind"),
      jacksonModule("datatype", "datatype-jdk8"),
      jacksonModule("datatype", "datatype-jsr310"),
      "tools.jackson.core" % "jackson-core"     % "3.3.0",
      "tools.jackson.core" % "jackson-databind" % "3.3.0",
      nettyModule("codec-http2"),
      nettyModule("codec-http"),
      nettyModule("handler-proxy"),
      nettyModule("codec-socks"),
      nettyModule("transport-native-unix-common"),
      nettyModule("resolver-dns")
    ),
    libraryDependencies ++= {
      val node        = "1.6.6"
      val sttpVersion = "3.11.0"
      Seq(
        "com.wavesplatform"              % "node-testkit"  % node % Test,
        "com.wavesplatform"              % "node"          % node % Provided,
        "com.softwaremill.sttp.client3" %% "core"          % sttpVersion,
        "com.softwaremill.sttp.client3" %% "play-json"     % sttpVersion,
        "com.github.jwt-scala"          %% "jwt-play-json" % "11.0.4",
        ("org.web3j"                     % "core"          % "4.13.0").excludeAll(
          ExclusionRule("org.slf4j", "slf4j-api"),
          ExclusionRule("org.bouncycastle", "bcprov-jdk15on"),
          ExclusionRule("software.amazon.awssdk", "kms"),
          ExclusionRule("com.github.jnr", "jnr-unixsocket")
        )
      )
    },
    Compile / packageDoc / publishArtifact := false,
    Test / unmanagedResourceDirectories += baseDirectory.value / "contracts" / "waves" / "src", // main.ride
    Universal / mappings += {
      val jar = (Compile / packageBin).value
      val id  = projectID.value
      val art = (Compile / packageBin / artifact).value
      jar -> ("lib/" + makeJarName(id.organization, id.name, id.revision, art.name, art.classifier))
    },
    Universal / mappings ++= universalDepMappings((Runtime / dependencyClasspath).value.filterNot { p =>
      p.get(moduleIDStr).map(parseModuleIDStrAttribute).exists { m =>
        m.organization == "org.scala-lang" ||
        m.organization.startsWith("com.fasterxml.jackson")
      }
    }),
    // Uncached: docker/target is outside sbt's tracked outputs, so a cache hit would skip the copy
    buildTarballsForDocker := Def.uncached {
      IO.copyFile(
        fileConverter.value.toPath((Universal / packageZipTarball).value).toFile,
        baseDirectory.value / "docker" / "target" / "consensus-client.tgz"
      )
    },
    // Uncached: building the image is a side effect sbt does not track
    docker := Def.uncached {
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
    },
    docker := docker.dependsOn(buildTarballsForDocker).value
  )

lazy val `consensus-client-it` = project
  .dependsOn(
    `consensus-client` % "compile;test->test"
  )
