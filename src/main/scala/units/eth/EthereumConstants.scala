package units.eth

import units.util.HexBytesConverter
import org.web3j.rlp.{RlpEncoder, RlpList}

object EthereumConstants {
  val GenesisBlockHeight = 0L
  val EmptyBlockHashHex  = "0x" + "0" * 64

  val OmmersHashRlp = rlpArray(hash(RlpEncoder.encode(new RlpList())))

  val EmptyRootHash    = hash(RlpEncoder.encode(rlpArray(Array.empty)))
  val EmptyRootHashHex = HexBytesConverter.toHex(EmptyRootHash)
  val EmptyRootHashRlp = rlpArray(EmptyRootHash)

  val NullHex = "0x"
  val NullRlp = rlpArray(Array[Byte]())

  val ZeroHex = "0x0"

  val EmptyLogsBloomHex = "0x" + "0" * 512
  val EmptyLogsBloomRlp = rlpString(EmptyLogsBloomHex)

  val EmptyPrevRandaoHex = "0x" + "0" * 64
  val EmptyPrevRandaoRlp = rlpString(EmptyPrevRandaoHex)

  val EmptyBlockNonceHex = "0x" + "0" * 16
  val EmptyBlockNonceRlp = rlpString(EmptyBlockNonceHex)

  val ZeroAddress = EthAddress.unsafeFrom("0x" + "0" * 40)
}
