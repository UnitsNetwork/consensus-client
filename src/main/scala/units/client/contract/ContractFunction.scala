package units.client.contract

import cats.syntax.either.*
import com.wavesplatform.common.merkle.Digest
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.CommonError
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms.{CONST_BYTESTR, CONST_LONG, CONST_STRING, EVALUATED, FUNCTION_CALL}
import org.web3j.utils.Numeric.cleanHexPrefix
import units.util.HexBytesConverter.toHexNoPrefix
import units.{BlockHash, ClientError, JobResult}

abstract class ContractFunction(baseName: String, extraArgs: Either[CommonError, List[EVALUATED]]) {
  def reference: BlockHash
  def version: Int
  def name: String = if (version >= 2) s"${baseName}_v$version" else baseName

  def toFunctionCall(
      blockHash: BlockHash,
      nativeTransfersRootHash: Digest,
      lastC2ENativeTransferIndex: Long,
      assetTransfersRootHash: Digest,
      lastC2EAssetTransferIndex: Long,
      lastAssetRegistrySyncedIndex: Long
  ): JobResult[FUNCTION_CALL] = (for {
    hash <- CONST_STRING(cleanHexPrefix(blockHash))
    ref  <- CONST_STRING(cleanHexPrefix(reference))
    ntrh <- CONST_STRING(toHexNoPrefix(nativeTransfersRootHash))
    itrh <- CONST_STRING(toHexNoPrefix(assetTransfersRootHash))
    xtra <- extraArgs
  } yield FUNCTION_CALL(
    FunctionHeader.User(name),
    List(hash, ref) ++ xtra ++ List(ntrh, CONST_LONG(lastC2ENativeTransferIndex)) ++
      (if (version >= 2) List(itrh, CONST_LONG(lastC2EAssetTransferIndex), CONST_LONG(lastAssetRegistrySyncedIndex)) else Nil)
  )).leftMap(e => ClientError(s"Error building function call for $name: $e"))
}

object ContractFunction {
  case class ExtendMainChain(reference: BlockHash, vrf: ByteStr, version: Int)
      extends ContractFunction("extendMainChain", CONST_BYTESTR(vrf).map(v => List(v)))

  case class AppendBlock(reference: BlockHash, version: Int) extends ContractFunction("appendBlock", Right(Nil))

  case class ExtendAltChain(reference: BlockHash, vrf: ByteStr, chainId: Long, version: Int)
      extends ContractFunction("extendAltChain", CONST_BYTESTR(vrf).map(v => List(v, CONST_LONG(chainId))))

  case class StartAltChain(reference: BlockHash, vrf: ByteStr, version: Int)
      extends ContractFunction("startAltChain", CONST_BYTESTR(vrf).map(v => List(v)))
}
