package units.el

import org.web3j.abi.datatypes.{AbiTypes, Type}
import org.web3j.abi.{FunctionReturnDecoder, TypeReference}

import java.util.Collections

object EvmEncoding {
  def decodeRevertReason(hexRevert: String): String =
    if (Option(hexRevert).isEmpty) "???"
    else {
      val cleanHex       = if (hexRevert.startsWith("0x")) hexRevert.drop(2) else hexRevert
      val errorSignature = "08c379a0" // Error(string)

      if (!cleanHex.startsWith(errorSignature)) throw new RuntimeException(s"Not a revert reason: $hexRevert")

      val strType           = TypeReference.create(AbiTypes.getType("string").asInstanceOf[Class[Type[?]]])
      val revertReasonTypes = Collections.singletonList(strType)

      val encodedReason = "0x" + cleanHex.drop(8)
      val decoded       = FunctionReturnDecoder.decode(encodedReason, revertReasonTypes)
      if (decoded.isEmpty) throw new RuntimeException(s"Unknown revert reason: $hexRevert")
      else decoded.get(0).getValue.asInstanceOf[String]
    }
}
