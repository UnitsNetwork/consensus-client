package units

import com.wavesplatform.common.merkle.Message

package object el {
  private val emptyData: Message = Array[Byte](0)
  def padData(data: Seq[Message], size: Int): Seq[Message] =
    if (data.size >= size) data
    else data ++ Array.fill(size - data.size)(emptyData)

  val MaxC2ENativeTransfers = 16
  val MinE2CTransfers       = 1024

  val C2ETopics: Seq[String] = List(StandardBridge.ERC20BridgeFinalized.Topic, StandardBridge.ETHBridgeFinalized.Topic)
  val E2CTopics: Seq[String] = List(NativeBridge.ElSentNativeEventTopic, StandardBridge.ERC20BridgeInitiated.Topic)
}
