package units.client.contract

import com.wavesplatform.account.Address
import com.wavesplatform.state.DataEntry

class ChainContractCombinedClient(recent: ChainContractClient, oldest: ChainContractClient) extends ChainContractClient {
  override def contract: Address = recent.contract

  override def extractData(key: String): Option[DataEntry[?]] =
    recent.extractData(key).orElse(oldest.extractData(key))
}
