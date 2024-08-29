package units.client.contract

import cats.kernel.Monoid
import com.wavesplatform.account.Address
import com.wavesplatform.api.http.StateSnapshotJson
import com.wavesplatform.state.{DataEntry, NewTransactionInfo, StateSnapshot, TxMeta}
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.utils.ScorexLogging
import play.api.libs.json.Json

class ChainContractSnapshotClient(val contract: Address, snapshot: StateSnapshot) extends ChainContractClient with ScorexLogging {

  private val totalSnapshot: StateSnapshot =
    Monoid[StateSnapshot].combineAll(snapshot +: snapshot.transactions.values.toSeq.map(_.snapshot))

  def logDataChanges(): Unit = if (totalSnapshot.accountData.nonEmpty) {
    snapshot.transactions.foreach {
      case (id, txInfo @ NewTransactionInfo(tx: InvokeScriptTransaction, txSnapshot, _, TxMeta.Status.Succeeded, _))
          if tx.dApp == contract && txSnapshot.accountData.nonEmpty => // Don't resolve a possible alias, because it is only for logs
        val dataJson = Json.toJson(StateSnapshotJson.fromSnapshot(txSnapshot, txInfo.status).accountData)
        log.trace(s"[$id] Data changes: $dataJson")

      case _ =>
    }
  }

  override def extractData(key: String): Option[DataEntry[?]] =
    totalSnapshot.accountData.get(contract).flatMap(_.get(key))
}
