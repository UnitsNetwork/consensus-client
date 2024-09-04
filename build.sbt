Global / onChangedBuildSource := ReloadOnSourceChanges

enablePlugins(UniversalDeployPlugin, GitVersioning)

git.useGitDescribe       := true
git.baseVersion          := "1.0.0"
git.uncommittedSignifier := Some("DIRTY")

scalaVersion     := "2.13.14"
organization     := "network.units"
organizationName := "Units Network"
name             := "consensus-client"
maintainer       := "Units Network Team"
resolvers ++= Resolver.sonatypeOssRepos("releases") ++ Resolver.sonatypeOssRepos("snapshots") ++ Seq(Resolver.mavenLocal)
libraryDependencies ++= Seq(
  "com.wavesplatform"              % "node"          % "1.5.7" % "provided",
  "com.softwaremill.sttp.client3"  % "core_2.13"     % "3.9.8",
  "com.softwaremill.sttp.client3" %% "play-json"     % "3.9.8",
  "com.github.jwt-scala"          %% "jwt-play-json" % "10.0.1"
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
