package units.network

import com.wavesplatform.network.BasicMessagesRepo.Spec
import com.wavesplatform.network.LegacyFrameCodec.MessageRawData
import com.wavesplatform.network.message.Message.MessageCode
import com.wavesplatform.network.{LegacyFrameCodec as LFC, PeerDatabase}

class LegacyFrameCodec(peerDatabase: PeerDatabase) extends LFC(peerDatabase) {
  override protected def filterBySpecOrChecksum(spec: Spec, checkSum: Array[MessageCode]): Boolean = true

  override protected def specsByCodes: Map[MessageCode, Spec] = BasicMessagesRepo.specsByCodes

  override protected def messageToRawData(msg: Any): MessageRawData = {
    val rawBytes = (msg: @unchecked) match {
      case rb: RawBytes               => rb
      case payloadMsg: PayloadMessage => RawBytes.from(PayloadSpec, payloadMsg)
    }

    MessageRawData(rawBytes.code, rawBytes.data)
  }

  protected def rawDataToMessage(rawData: MessageRawData): AnyRef =
    RawBytes(rawData.code, rawData.data)
}
