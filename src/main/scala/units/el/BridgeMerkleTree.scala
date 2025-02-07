package units.el

import cats.syntax.traverse.*
import com.wavesplatform.common.merkle.{Digest, Merkle}
import com.wavesplatform.utils.EthEncoding
import units.client.engine.model.GetLogsResponseEntry

trait BridgeMerkleTree[ElEventT] {
  def exactTransfersNumber: Int

  def encodeArgs(x: ElEventT): Array[Byte]
  def decodeLog(log: GetLogsResponseEntry): Either[String, ElEventT]

  def mkTransfersHash(elRawLogs: Seq[GetLogsResponseEntry]): Either[String, Digest] =
    for {
      bridgeEvents <- elRawLogs.traverse { l =>
        decodeLog(l).left.map { e =>
          s"Log decoding error in ${l.data}: $e. Try to upgrade your consensus client"
        }
      }
    } yield {
      if (bridgeEvents.isEmpty) Array.emptyByteArray
      else {
        val data     = bridgeEvents.map(encodeArgs)
        val levels   = Merkle.mkLevels(padData(data, exactTransfersNumber))
        val rootHash = levels.head.head
        rootHash
      }
    }

  def mkTransferProofs(events: Seq[ElEventT], transferIndex: Int): Seq[Digest] =
    if (events.isEmpty) Nil
    else {
      val data   = events.map(encodeArgs)
      val levels = Merkle.mkLevels(padData(data, exactTransfersNumber))
      Merkle.mkProofs(transferIndex, levels)
    }
}
