package units.client.contract

import com.wavesplatform.common.merkle.Digest
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.CommonError
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms.{CONST_BYTESTR, CONST_LONG, CONST_STRING, EVALUATED, FUNCTION_CALL}
import units.{BlockHash, ClientError, Job}
import units.util.HexBytesConverter.toHexNoPrefix
import cats.syntax.either.*

abstract class ContractFunction(name: String, reference: BlockHash, extraArgs: Either[CommonError, List[EVALUATED]]) {
  def toFunctionCall(blockHash: BlockHash, transfersRootHash: Digest, lastClToElTransferIndex: Long): Job[FUNCTION_CALL] = (for {
    hash <- CONST_STRING(blockHash)
    ref  <- CONST_STRING(reference)
    trh  <- CONST_STRING(toHexNoPrefix(transfersRootHash))
    xtra <- extraArgs
  } yield FUNCTION_CALL(
    FunctionHeader.User(name),
    List(hash, ref) ++ xtra ++ List(trh, CONST_LONG(lastClToElTransferIndex))
  )).leftMap(e => ClientError(s"Error building function call for $name: $e"))
}

object ContractFunction {
  case class ExtendMainChain(reference: BlockHash, vrf: ByteStr)
      extends ContractFunction("extendMainChain_v4", reference, CONST_BYTESTR(vrf).map(v => List(v)))

  case class AppendBlock(reference: BlockHash) extends ContractFunction("appendBlock_v3", reference, Right(Nil))

  case class ExtendAltChain(reference: BlockHash, vrf: ByteStr, chainId: Long)
      extends ContractFunction("extendAltChain_v4", reference, CONST_BYTESTR(vrf).map(v => List(v, CONST_LONG(chainId))))

  case class StartAltChain(reference: BlockHash, vrf: ByteStr)
      extends ContractFunction("startAltChain_v4", reference, CONST_BYTESTR(vrf).map(v => List(v)))
}
