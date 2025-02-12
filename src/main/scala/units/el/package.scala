package units

import com.wavesplatform.common.merkle.Message

package object el {
  type DepositedTransaction = DepositedTransaction.Type

  private val emptyData: Message = Array[Byte](0)
  def padData(data: Seq[Message], size: Int): Seq[Message] =
    if (data.size >= size) data
    else data ++ Array.fill(size - data.size)(emptyData)

  val MaxC2ENativeTransfers = 16
  val MinE2CTransfers       = 1024

  val C2ETopics = List(StandardBridge.ERC20BridgeFinalized)
  val E2CTopics = List(NativeBridge.ElSentNativeEventTopic, StandardBridge.ERC20BridgeInitiated)
}
