package units

import com.wavesplatform.account.Address
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.settings.*
import pureconfig.ConfigReader
import units.client.JsonRpcClient

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
    jwtSecretFile: Option[String]
                       )derives ConfigReader {
  lazy val chainContractAddress: Address = Address.fromString(chainContract).explicitGet()

  val jsonRpcClient = JsonRpcClient.Config(
    apiUrl = executionClientAddress,
    apiRequestRetries = apiRequestRetries,
    apiRequestRetryWaitTime = apiRequestRetryWaitTime
  )
}
