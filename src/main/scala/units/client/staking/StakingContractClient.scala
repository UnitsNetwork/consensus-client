package units.client.staking

import com.wavesplatform.account.Address
import com.wavesplatform.state.{DataEntry, StringDataEntry}
import com.wavesplatform.utils.ScorexLogging

import scala.util.Try

class StakingContractClient(getDataF: String => Option[DataEntry[?]]) extends ScorexLogging {

  def getL2mpBalance(miner: Address, height: Int): Long = {
    val dataEntry = getDataF("%s__" + miner.toString)
    dataEntry match {
      case Some(StringDataEntry(_, vstr)) =>
        val parts = vstr.split("__")
        val balance = (for {
          prevH <- parts.lift(1).flatMap(v => Try(v.toInt).toOption)
          prevB <- parts.lift(2).flatMap(v => Try(v.toLong).toOption)
          nextH <- parts.lift(3).flatMap(v => Try(v.toInt).toOption)
          nextB <- parts.lift(4).flatMap(v => Try(v.toLong).toOption)
        } yield
          if (height >= nextH) nextB
          else if (height >= prevH) prevB
          else 0L).getOrElse(0L)
        balance
      case _ => 0L
    }
  }
}
