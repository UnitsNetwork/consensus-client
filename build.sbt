import com.github.sbt.git.SbtGit.GitKeys.gitCurrentBranch

enablePlugins(UniversalDeployPlugin, GitVersioning, sbtdocker.DockerPlugin, VersionObject)

git.useGitDescribe       := true
git.baseVersion          := "1.1.0"
git.uncommittedSignifier := Some("DIRTY")

inScope(Global)(
  Seq(
    onChangedBuildSource := ReloadOnSourceChanges,
    scalaVersion         := "3.7.1",
    organization         := "network.units",
    organizationName     := "Units Network",
    resolvers ++= Seq(Resolver.sonatypeCentralSnapshots, Resolver.mavenLocal),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-language:postfixOps"
    )
  )
)

name       := "consensus-client"
maintainer := "Units Network Team"

libraryDependencies ++= {
  val node        = "1.5.11-SNAPSHOT"
  val sttpVersion = "3.10.3"
  Seq(
    "com.wavesplatform"              % "node-testkit"  % node % Test,
    "com.wavesplatform"              % "node"          % node % Provided,
    "com.softwaremill.sttp.client3" %% "core"          % sttpVersion,
    "com.softwaremill.sttp.client3" %% "play-json"     % sttpVersion,
    "com.github.jwt-scala"          %% "jwt-play-json" % "10.0.4",
    "org.web3j"                      % "core"          % "4.9.8"
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

inTask(docker)(
  Seq(
    imageNames := Seq(
      ImageName(s"consensus-client:${gitCurrentBranch.value}"), // Integration tests
      ImageName("consensus-client:local")                       // local-network
    ),
    dockerfile := NativeDockerfile(baseDirectory.value / "docker" / "Dockerfile"),
    buildOptions := BuildOptions(
      pullBaseImage = BuildOptions.Pull.IfMissing
    )
  )
)

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
