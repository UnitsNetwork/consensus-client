import sbt.taskKey

object ConsensusClientTasks {
  lazy val buildTarballsForDocker = taskKey[Unit]("Package consensus-client tarball and copy it to docker/target")
}
