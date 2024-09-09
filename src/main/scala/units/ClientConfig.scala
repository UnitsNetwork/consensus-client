package units

import com.wavesplatform.account.Address
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.settings.*
import net.ceedubs.ficus.Ficus.*
import net.ceedubs.ficus.readers.ArbitraryTypeReader.arbitraryTypeValueReader
import net.ceedubs.ficus.readers.{Generated, ValueReader}
import net.ceedubs.ficus.readers.namemappers.implicits.hyphenCase

import scala.concurrent.duration.FiniteDuration

case class ClientConfig(
    chainContract: String,
    executionClientAddress: String,
    apiRequestRetries: Int,
    apiRequestRetryWaitTime: FiniteDuration,
    blockDelay: FiniteDuration,
    blockSyncRequestTimeout: FiniteDuration,
    network: NetworkSettings,
    miningEnable: Boolean,
    syncInterval: FiniteDuration,
    jwtSecretFile: Option[String]
) {
  lazy val chainContractAddress: Address = Address.fromString(chainContract).explicitGet()
}

object ClientConfig {
  implicit val valueReader: Generated[ValueReader[ClientConfig]] = arbitraryTypeValueReader
}
