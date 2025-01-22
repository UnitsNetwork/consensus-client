package units

import com.wavesplatform.common.merkle.Message

package object el {
  type DepositedTransaction = DepositedTransaction.Type

  private val emptyData: Message = Array[Byte](0)
  def padData(data: Seq[Message], size: Int): Seq[Message] = {
    require(data.size <= size, s"data.size=${data.size} must be <= size=$size")
    if (data.size == size) data
    else data ++ Array.fill(size - data.size)(emptyData)
  }
}
