package units

import com.wavesplatform.block.Block.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.lang.directives.values.V8
import com.wavesplatform.lang.v1.compiler.Terms.*
import com.wavesplatform.lang.v1.compiler.TestCompiler
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.transaction.TxHelpers.*
import units.el.BridgeMerkleTree

class RideCreateMerkleRootTest extends BaseTestSuite {

  withDomain(TransactionStateSnapshot, AddrWithBalance.enoughBalances(secondSigner)) { d =>
    val dApp = TestCompiler(V8).compileContract(
      """
        | @Callable(i)
        | func merkle(proof: List[ByteVector], failedTransferIndex: Int, transferIndexInBlock: Int) = {
        |   let valueBytes = blake2b256(failedTransferIndex.toBytes())
        |   [ BinaryEntry("root", createMerkleRoot(proof, valueBytes, transferIndexInBlock)) ]
        | }
      """.stripMargin
    )
    d.appendBlock(d.createBlock(PlainBlockVersion, Seq(setScript(secondSigner, dApp))))

    val failedC2ETransferIndexes = List(11L, 12L, 103L, 104L)
    val transferIndex            = 103L
    val transferIndexInBlock     = failedC2ETransferIndexes.indexOf(transferIndex)

    val root: Array[Byte] = BridgeMerkleTree.getFailedTransfersRootHash(failedC2ETransferIndexes)
    val proofs: Seq[Array[Byte]] = BridgeMerkleTree
      .mkFailedTransferProofs(failedC2ETransferIndexes, transferIndexInBlock)
      .reverse

    val digests  = ARR(proofs.map(b => CONST_BYTESTR(ByteStr(b)).explicitGet()).toVector, limited = false).explicitGet()
    val invokeTx = invoke(func = Some("merkle"), args = Seq(digests, CONST_LONG(transferIndex), CONST_LONG(transferIndexInBlock)))
    d.appendBlock(d.createBlock(PlainBlockVersion, Seq(invokeTx)))

    val actual = d.blockchain.accountData(secondAddress, "root").get.value
    actual shouldBe ByteStr(root)
  }

}
