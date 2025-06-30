package units

import com.wavesplatform.account.{Address, PrivateKey}
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.settings.*
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader
import units.client.JsonRpcClient

import scala.concurrent.duration.FiniteDuration

case class ClientConfig(
    chainContract: String,
    executionClientAddress: String,
    apiRequestRetries: Int,
    apiRequestRetryWaitTime: FiniteDuration,
    blockDelay: FiniteDuration,
    firstBlockMinDelay: FiniteDuration,
    blockSyncRequestTimeout: FiniteDuration,
    network: NetworkSettings,
    miningEnable: Boolean,
    jwtSecretFile: Option[String],
    privateKeys: Seq[PrivateKey] = Seq.empty
) {
  lazy val chainContractAddress: Address = Address.fromString(chainContract).explicitGet()

  val jsonRpcClient = JsonRpcClient.Config(
    apiUrl = executionClientAddress,
    apiRequestRetries = apiRequestRetries,
    apiRequestRetryWaitTime = apiRequestRetryWaitTime
  )
}

object ClientConfig {
  given ConfigReader[ClientConfig] = deriveReader
}
