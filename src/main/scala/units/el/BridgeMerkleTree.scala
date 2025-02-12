package units.el

import cats.syntax.traverse.*
import com.wavesplatform.common.merkle.{Digest, Merkle}
import com.wavesplatform.utils.EthEncoding
import units.client.engine.model.GetLogsResponseEntry

trait BridgeMerkleTree[ElEventT] {
  def exactTransfersNumber: Int

  def encodeArgsForMerkleTree(x: ElEventT): Array[Byte]
  def decodeLog(log: GetLogsResponseEntry): Either[String, ElEventT]

  def mkTransferProofs(events: Seq[ElEventT], transferIndex: Int): Seq[Digest] =
    if (events.isEmpty) Nil
    else {
      val data   = events.map(encodeArgsForMerkleTree)
      val levels = Merkle.mkLevels(padData(data, exactTransfersNumber))
      Merkle.mkProofs(transferIndex, levels)
    }
}

object BridgeMerkleTree {
  def mkTransfersHash(items: List[Array[Byte]], padding: Int): Digest =
    if (items.isEmpty) Array.emptyByteArray
    else {
      val levels   = Merkle.mkLevels(padData(items, padding))
      val rootHash = levels.head.head
      rootHash
    }
}
