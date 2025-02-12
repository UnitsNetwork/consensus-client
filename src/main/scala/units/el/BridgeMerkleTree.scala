package units.el

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.wavesplatform.common.merkle.{Digest, Merkle}
import units.client.engine.model.GetLogsResponseEntry
import units.util.HexBytesConverter

trait BridgeMerkleTree[ElEventT] {
  def encodeArgsForMerkleTree(x: ElEventT): Array[Byte]
  def decodeLog(log: GetLogsResponseEntry): Either[String, ElEventT]
}

object BridgeMerkleTree {
  def getE2CTransfersRootHash(ecBlockLogs: List[GetLogsResponseEntry]): Either[String, Digest] =
    getData(ecBlockLogs).map(BridgeMerkleTree.mkTransfersHash)

  def mkTransfersHash(data: List[Array[Byte]]): Digest =
    if (data.isEmpty) Array.emptyByteArray
    else {
      val levels   = Merkle.mkLevels(padData(data, MinE2CTransfers))
      val rootHash = levels.head.head
      rootHash
    }

  def mkTransferProofs(ecBlockLogs: List[GetLogsResponseEntry], transferIndex: Int): Either[String, Seq[Digest]] =
    if (ecBlockLogs.isEmpty) Right(Nil)
    else
      getData(ecBlockLogs).map { data =>
        val levels = Merkle.mkLevels(padData(data, MinE2CTransfers))
        Merkle.mkProofs(transferIndex, levels)
      }

  def getData(ecBlockLogs: List[GetLogsResponseEntry]): Either[String, List[Array[Byte]]] =
    ecBlockLogs.flatTraverse {
      case x if x.topics.contains(NativeBridge.ElSentNativeEventTopic) =>
        NativeBridge.ElSentNativeEvent
          .decodeLog(x.data)
          .map(NativeBridge.ElSentNativeEvent.encodeArgs)
          .map(x => List(HexBytesConverter.toBytes(x)))

      case x if x.topics.contains(StandardBridge.ERC20BridgeInitiated.Topic) =>
        StandardBridge.ERC20BridgeInitiated
          .decodeLog(x)
          .map(StandardBridge.ERC20BridgeInitiated.encodeArgsForMerkleTree)
          .map(List(_))

      case _ => List.empty[Array[Byte]].asRight
    }
}
