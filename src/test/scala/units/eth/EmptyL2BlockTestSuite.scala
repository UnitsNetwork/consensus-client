package units.eth

import com.wavesplatform.test.BaseSuite
import org.scalatest.freespec.AnyFreeSpec
import org.web3j.abi.datatypes.generated.Uint256
import play.api.libs.json.Json
import units.BlockHash
import units.client.engine.model.EcBlock
import units.eth.EmptyL2Block.Params

class EmptyL2BlockTestSuite extends AnyFreeSpec with BaseSuite {
  private val DefaultFeeRecipient = EthAddress.unsafeFrom("0x283c3c6ad2043af4d4e7d261809260fdab4a62d2")

  "mkExecutionPayload" in {
    // Got from eth_getBlockByHash
    val parentEcBlockJson =
      """{
        |  "number": "0xf",
        |  "hash": "0x296e3d6de01dbe3c109e13799d6efd2b68469075149ee4e1d8d644765e2cd620",
        |  "mixHash": "0x0000000000000000000000000000000000000000000000000000000000000000",
        |  "parentHash": "0x212f33e2d5888f549b85500bad79be55b03dff5ebe3de51106e7134abdb5a2cd",
        |  "nonce": "0x0000000000000000",
        |  "sha3Uncles": "0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347",
        |  "logsBloom": "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        |  "transactionsRoot": "0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421",
        |  "stateRoot": "0x02f216de6553e02cc0e6bc7c21c7b777022eca84cc83e847d2d68e07b019adfd",
        |  "receiptsRoot": "0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421",
        |  "miner": "0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f9",
        |  "difficulty": "0x0",
        |  "totalDifficulty": "0x0",
        |  "extraData": "0x",
        |  "baseFeePerGas": "0x80aed38",
        |  "size": "0x264",
        |  "gasLimit": "0x103c683",
        |  "gasUsed": "0x0",
        |  "timestamp": "0x65f7e7ea",
        |  "uncles": [],
        |  "transactions": [],
        |  "withdrawalsRoot": "0x261c8c6536fb17ecd1cd6c889eec98d4485eafc77330d09f0da33dbd9c25ce53",
        |  "withdrawals": [
        |    {
        |      "index": "0x0",
        |      "validatorIndex": "0x0",
        |      "address": "0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f9",
        |      "amount": "0x77359400"
        |    }
        |  ],
        |  "blobGasUsed": "0x0",
        |  "excessBlobGas": "0x0",
        |  "parentBeaconBlockRoot": "0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"
        |}""".stripMargin

    val parentEcBlock = Json.parse(parentEcBlockJson).as[EcBlock]
    EmptyL2Block.mkExecutionPayload(parentEcBlock) shouldBe Json.parse(
      """{
        |  "parentHash": "0x296e3d6de01dbe3c109e13799d6efd2b68469075149ee4e1d8d644765e2cd620",
        |  "feeRecipient": "0x0000000000000000000000000000000000000000",
        |  "stateRoot": "0x02f216de6553e02cc0e6bc7c21c7b777022eca84cc83e847d2d68e07b019adfd",
        |  "receiptsRoot": "0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421",
        |  "logsBloom": "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        |  "prevRandao": "0x0000000000000000000000000000000000000000000000000000000000000000",
        |  "blockNumber": "0x10",
        |  "gasLimit": "0x103c683",
        |  "gasUsed": "0x0",
        |  "timestamp": "0x65f7e7eb",
        |  "extraData": "0x",
        |  "baseFeePerGas": "0x7098f91",
        |  "blockHash": "0x9c2eb1960eef14f449bbe21d582c8b66ba2a2793ab2d140f85186c8803c7d9cb",
        |  "transactions": [],
        |  "withdrawals": [],
        |  "blobGasUsed": "0x0",
        |  "excessBlobGas": "0x0"
        |}""".stripMargin
    )
  }

  "calculateHash" - {
    "hash 1" in {
      val expected = "0xfb0c23d4b394710700e8b4588905cc0a123c088044a6326266280798cb6a5a92"
      val actual = EmptyL2Block.calculateHash(
        Params(
          parentHash = BlockHash("0x49a8da0a609fbf1d68535a0766ecb0fe35d9cdac6ba459281daa944fa4c273b9"),
          parentStateRoot = "0xc0687a72deb7cec7caa72e99a985f3475cb780321d0572a9975fa7638c29c4e1",
          parentGasLimit = 0x1c9c380,
          newBlockTimestamp = 0x18df90fbab7L,
          newBlockNumber = 0x60c4,
          baseFee = new Uint256(0x7),
          feeRecipient = DefaultFeeRecipient
        )
      )
      actual shouldBe expected
    }

    "hash 2" in {
      val expected = "0x86ecb0dc1b4f2a2f90f9cac69831ad9c3fc029e59d900b84b37fbee5e9963275"
      val actual = EmptyL2Block.calculateHash(
        Params(
          parentHash = BlockHash("0xfd07fb55cefbec6c0a74aa37058dd0462b71c6ef385ec5388cd2b2092618e895"),
          parentStateRoot = "0xd254bdd872c29ea362569bc5427843b416efca19a2c8e60af941b694cf48e40d",
          parentGasLimit = 0x1c9c380,
          newBlockTimestamp = 0x18e2e4f9e2fL,
          newBlockNumber = 0xfd68,
          baseFee = new Uint256(0x7),
          feeRecipient = DefaultFeeRecipient
        )
      )
      actual shouldBe expected
    }
  }

  "calculateGasFee" - {
    "0x11ebac8b" in {
      EmptyL2Block.calculateGasFee(
        parentGasLimit = 0x1002000,
        parentBaseFeePerGas = new Uint256(0x147b0e55),
        0
      ) shouldBe new Uint256(0x11ebac8b)
    }

    "0xfae36fa" in {
      EmptyL2Block.calculateGasFee(
        parentGasLimit = 0x1002800,
        parentBaseFeePerGas = new Uint256(0x11ebac8b),
        0
      ) shouldBe new Uint256(0xfae36fa)
    }

    // TODO test with parentGasUsed != 0
    "0x1ac012b7" in {
      EmptyL2Block.calculateGasFee(
        parentGasLimit = 0x1001400,
        parentBaseFeePerGas = new Uint256(0x1e925e88),
        0
      ) shouldBe new Uint256(0x1ac012b7)
    }
  }
}
