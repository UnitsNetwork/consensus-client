package units

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import units.ELUpdater.{NoFinalizedBlockHash, finalizedHashFor}
import units.client.L2BlockLike
import units.eth.{EthAddress, EthereumConstants}

class FinalizedHashForTestSuite extends AnyFreeSpec with Matchers {
  private def block(blockHeight: Long, n: Int): L2BlockLike = {
    val h = BlockHash(f"0x$n%064x")
    new L2BlockLike {
      override val hash: BlockHash                  = h
      override val parentHash: BlockHash            = BlockHash(f"0x${n + 1000}%064x")
      override val height: Long                     = blockHeight
      override val minerRewardL2Address: EthAddress = EthAddress.empty
    }
  }

  private val finalized = block(10, 1)

  "finalizedHashFor" - {
    "reports the contract's finalized block when the head is above it" in {
      finalizedHashFor(block(11, 2), finalized) shouldBe finalized.hash
    }

    "reports the contract's finalized block when it is the head" in {
      finalizedHashFor(finalized, finalized) shouldBe finalized.hash
    }

    "reports no finalized block when the head is below the contract's finalized block" in {
      // The head may be a block that never made it to the contract. Reporting it as finalized makes EL freeze it for good.
      finalizedHashFor(block(9, 3), finalized) shouldBe NoFinalizedBlockHash
    }
  }

  "NoFinalizedBlockHash is the all-zero hash" in {
    NoFinalizedBlockHash.str shouldBe EthereumConstants.EmptyBlockHashHex
    NoFinalizedBlockHash.str shouldBe "0x" + "0" * 64
  }
}
