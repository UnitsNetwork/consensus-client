package units.client.contract

import com.wavesplatform.account.Address
import com.wavesplatform.state.{Blockchain, DataEntry}

class ChainContractStateClient(val contract: Address, blockchain: Blockchain) extends ChainContractClient {

  override def extractData(key: String): Option[DataEntry[?]] =
    blockchain.accountData(contract, key)
}
