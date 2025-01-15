import com.github.sbt.git.SbtGit.GitKeys.gitCurrentBranch

enablePlugins(UniversalDeployPlugin, GitVersioning, sbtdocker.DockerPlugin)

git.useGitDescribe       := true
git.baseVersion          := "1.0.0"
git.uncommittedSignifier := Some("DIRTY")

inScope(Global)(
  Seq(
    onChangedBuildSource := ReloadOnSourceChanges,
    scalaVersion         := "2.13.16",
    organization         := "network.units",
    organizationName     := "Units Network",
    resolvers ++= Resolver.sonatypeOssRepos("releases") ++ Resolver.sonatypeOssRepos("snapshots") ++ Seq(Resolver.mavenLocal),
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
  )
)

name       := "consensus-client"
maintainer := "Units Network Team"

libraryDependencies ++= {
  val node = "1.5.9"
  val sttpVersion = "3.10.2"
  Seq(
    "com.wavesplatform"              % "node-testkit"  % node % Test,
    "com.wavesplatform"              % "node"          % node % Provided,
    "com.softwaremill.sttp.client3"  % "core_2.13"     % sttpVersion,
    "com.softwaremill.sttp.client3" %% "play-json"     % sttpVersion,
    "com.github.jwt-scala"          %% "jwt-play-json" % "10.0.1"
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
    dockerfile   := NativeDockerfile(baseDirectory.value / "docker" / "Dockerfile"),
    buildOptions := BuildOptions(cache = true)
  )
)

docker := docker.dependsOn(LocalRootProject / buildTarballsForDocker).value

lazy val `consensus-client` = project.in(file("."))

lazy val `consensus-client-it` = project
  .dependsOn(
    `consensus-client` % "compile;test->test"
  )
