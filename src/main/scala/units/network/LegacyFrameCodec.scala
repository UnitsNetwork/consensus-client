package units.network

import units.NetworkL2Block
import com.wavesplatform.network.BasicMessagesRepo.Spec
import com.wavesplatform.network.LegacyFrameCodec.MessageRawData
import com.wavesplatform.network.message.Message.MessageCode
import com.wavesplatform.network.{LegacyFrameCodec as LFC, PeerDatabase}

class LegacyFrameCodec(peerDatabase: PeerDatabase) extends LFC(peerDatabase) {
  override protected def specsByCodes: Map[MessageCode, Spec] = BasicMessagesRepo.specsByCodes

  override protected def messageToRawData(msg: Any): MessageRawData = {
    val rawBytesL2 = (msg: @unchecked) match {
      case rb: RawBytes   => rb
      case block: NetworkL2Block => RawBytes.from(BlockSpec, block)
    }

    MessageRawData(rawBytesL2.code, rawBytesL2.data)
  }

  protected def rawDataToMessage(rawData: MessageRawData): AnyRef =
    RawBytes(rawData.code, rawData.data)
}
