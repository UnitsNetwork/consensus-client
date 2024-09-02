package network.units.consensus.client.contract

import com.wavesplatform.account.PublicKey
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.test.BaseSuite
import org.scalatest.freespec.AnyFreeSpec
import units.eth.EthereumConstants
import units.util.HexBytesConverter

class ChainContractTestSuite extends AnyFreeSpec with BaseSuite {
  // Use this to generate a block meta
  "blockMetaKey" in {
    val chainId        = 'D'.toByte
    val emptyPk        = PublicKey(Array.fill[Byte](32)(0))
    val emptyPkAddress = emptyPk.toAddress(chainId)

    val chainHeight = EthereumConstants.GenesisBlockHeight
    val epoch       = 0L
    val reference   = EthereumConstants.EmptyBlockHashHex
    val miner       = emptyPkAddress

    // See ChainContractClient.getBlock
    val blockMetaValue = ByteStr.fromLong(chainHeight) ++
      ByteStr.fromLong(epoch) ++
      ByteStr(HexBytesConverter.toBytes(reference)) ++
      ByteStr(miner.bytes)

    // val minerEthRewardAddress = EthereumConstants.EmptyFeeRecipient
    // val genesisBlockHashHex   = "c35c3befcf9eb87bc7ffa467225f3eac8e96e6d3cd9ad6ab3bebbbae0b706031"
    // println(
    //   s"""miner${miner}RewardAddress: ${ByteStr(HexBytesConverter.toBytes(minerEthRewardAddress)).base64}
    //      |blockMeta$genesisBlockHashHex: ${blockMetaValue.base64}
    //      |chain0FirstBlock: ${ByteStr(HexBytesConverter.toBytes(genesisBlockHashHex)).base64}""".stripMargin
    // )

    blockMetaValue.base64 shouldBe "base64:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAUQ7/yyDy88aPjf+qhzZz/b3yDC2lU8yei4="
  }
}
