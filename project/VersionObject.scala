import sbt.*
import sbt.Keys.*

object VersionObject extends AutoPlugin {
  override def projectSettings: Seq[Def.Setting[?]] = Seq(
    (Compile / sourceGenerators) += Def.task {
      val packageName = "units"
      val versionFile = (Compile / sourceManaged).value / s"${packageName.replace('.', '/')}/Version.scala"

      IO.write(
        versionFile,
        s"""package $packageName
           |
           |object Version {
           |  val VersionString = "${version.value}"
           |}
           |""".stripMargin
      )

      Seq(versionFile)
    }
  )
}
