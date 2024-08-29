package units.client.contract

import cats.syntax.option.*
import units.client.contract.ContractFunction.*

sealed abstract class ContractFunction(
    baseName: String,
    val chainIdOpt: Option[Long] = none,
    val epochOpt: Option[Int] = none
) {
  def version: Version

  val name: String = s"$baseName${version.mineFunctionsSuffix}"
}

object ContractFunction {
  val ExtendMainChainBaseName = "extendMainChain"
  val AppendBlockBaseName     = "appendBlock"
  val ExtendAltChainBaseName  = "extendAltChain"
  val StartAltChainBaseName   = "startAltChain"

  case class ExtendMainChain(epoch: Int, version: Version)               extends ContractFunction(ExtendMainChainBaseName, epochOpt = epoch.some)
  case class AppendBlock(version: Version)                               extends ContractFunction(AppendBlockBaseName)
  case class ExtendAltChain(chainId: Long, epoch: Int, version: Version) extends ContractFunction(ExtendAltChainBaseName, chainId.some, epoch.some)
  case class StartAltChain(epoch: Int, version: Version)                 extends ContractFunction(StartAltChainBaseName, epochOpt = epoch.some)

  val AllNames: List[String] = for {
    function <- List(ExtendMainChainBaseName, AppendBlockBaseName, ExtendAltChainBaseName, StartAltChainBaseName)
    version  <- Version.All
  } yield function + version.mineFunctionsSuffix

  val StartAltChainNames = AllNames.filter(_.startsWith(StartAltChainBaseName))

  sealed abstract class Version(protected val n: Int) extends Ordered[Version] with Product with Serializable {
    val mineFunctionsSuffix: String = s"_v$n"

    override def compare(that: Version): Int = java.lang.Integer.compare(n, that.n)
    override def canEqual(that: Any): Boolean = that match {
      case _: Version => true
      case _          => false
    }
  }

  case object V2 extends Version(2) // Supports EL to CL transfers
  case object V3 extends Version(3) // Supports CL to EL transfers
  object Version {
    val All = List(V2, V3)
  }
}
