package units

import com.wavesplatform.common.utils.Base64
import play.api.libs.json.Json
import units.client.engine.model.GetLogsResponseEntry
import units.el.BridgeMerkleTree

class BridgeMerkleTreeTestSuite extends BaseTestSuite {
  private val jsonLogs =
    """[
      |  {
      |    "address": "0x0000000000000000000000000000000000006a7e",
      |    "topics": [
      |      "0xfeadaf04de8d7c2594453835b9a93b747e20e7a09a7fdb9280579a6dbaf131a8"
      |    ],
      |    "data": "0x03cf4d50c1450640175edbb0825272985fd392a50000000000000000000000000000000000000000000000000000000000000000000000000000000005f5e100",
      |    "blockNumber": "0x230",
      |    "transactionHash": "0x80f3b8111d420c1cea1fecccb5a2d648ac1bd30967177fe7f0f1045ab82d83b7",
      |    "transactionIndex": "0x0",
      |    "blockHash": "0x8509ce1d21b924e86a2592cfecce18d680aa60f23c06d2ac2e0279575f49c427",
      |    "logIndex": "0x0",
      |    "removed": false
      |  },
      |  {
      |    "address": "0xa50a51c09a5c451c52bb714527e1974b686d8e77",
      |    "topics": [
      |      "0xa1c5906bb7b8a0c21149c912eb63f5b7f8a4ea3b1b46f5538f8c5936cdf963f4",
      |      "0x0000000000000000000000009b8397f1b0fecd3a1a40cdd5e8221fa461898517",
      |      "0x000000000000000000000000fe3b557e8fb62b89f4916b721be55ceb828dbd73",
      |      "0x00000000000000000000000003cf4d50c1450640175edbb0825272985fd392a5"
      |    ],
      |    "data": "0x0000000000000000000000000000000000000000000000000000000005f5e100",
      |    "blockNumber": "0x230",
      |    "transactionHash": "0xe98934ef37e1a7dde4dbb41865c729cb7bdc94b83402661b3dbcb430d8d01ed4",
      |    "transactionIndex": "0x1",
      |    "blockHash": "0x8509ce1d21b924e86a2592cfecce18d680aa60f23c06d2ac2e0279575f49c427",
      |    "logIndex": "0x2",
      |    "removed": false
      |  }
      |]""".stripMargin

  private val logs = Json.parse(jsonLogs).as[List[GetLogsResponseEntry]]

  private def revertedProofsFor(i: Int): Seq[String] = BridgeMerkleTree.mkTransferProofs(logs, i).value.map(Base64.encode).reverse

  "mkTransferProofs" - {
    "for index 0" in {
      revertedProofsFor(0) shouldBe List(
        "a+egiFCA92OE6nZooRqSlc4Lm5KgmIKQAcaV7bfFhIM=",
        "vtGUDqNXpl9vJ08buDbsMrxa1msEnjMTgyDqG/fFu+I=",
        "fvS9csQMlExN5HDhea8my1s3dwbjNvwidCk5jfrLlrg=",
        "eePa4Vl4N4m6byG6lCyECWOeTxrkgcDiTz3glf0JrAk=",
        "/Ls/CWWMVEH9u92tDQzUyEE6AD4RLcbZ15VjWdeIe9g=",
        "21H/69hAtwCmiGL9asVY0eE6phIsO5PgzagSf6D3z+8=",
        "gdzu7+4K8fw8u6PGsn8HFArKM0oD9b9EPX5TqKIUQDc=",
        "HhsvWCH++uFjxr1mWTN8BL5ILYVQ/bRWElkJzHJRSac=",
        "EoiSEyp8ybh8snXqsTwA9xkuc8Ro6hEOlQUz4bY5VO0=",
        "WQ5w0oXbjfb+gYTTH5vhm+zh4puDVJZGNZjDQprj2/w="
      )
    }

    "for index 1" in {
      revertedProofsFor(1) shouldBe List(
        "6PfQK0YI8RD4Ksf33IlZ4sHaO/UyPg3kZ3n5zzus/j4=",
        "vtGUDqNXpl9vJ08buDbsMrxa1msEnjMTgyDqG/fFu+I=",
        "fvS9csQMlExN5HDhea8my1s3dwbjNvwidCk5jfrLlrg=",
        "eePa4Vl4N4m6byG6lCyECWOeTxrkgcDiTz3glf0JrAk=",
        "/Ls/CWWMVEH9u92tDQzUyEE6AD4RLcbZ15VjWdeIe9g=",
        "21H/69hAtwCmiGL9asVY0eE6phIsO5PgzagSf6D3z+8=",
        "gdzu7+4K8fw8u6PGsn8HFArKM0oD9b9EPX5TqKIUQDc=",
        "HhsvWCH++uFjxr1mWTN8BL5ILYVQ/bRWElkJzHJRSac=",
        "EoiSEyp8ybh8snXqsTwA9xkuc8Ro6hEOlQUz4bY5VO0=",
        "WQ5w0oXbjfb+gYTTH5vhm+zh4puDVJZGNZjDQprj2/w="
      )
    }
  }

  "getE2CTransfersRootHash" in {
    val actualRootHash = Base64.encode(BridgeMerkleTree.getE2CTransfersRootHash(logs).value)
    actualRootHash shouldBe "TE571PexW4ErsrcEKhn1wRaVjGW/RkvaaOEFN/AMXuQ="
  }

  "getFailedTransfersRootHash, 0 indexes" in {
    val failedTransfersHash = Base64.encode(BridgeMerkleTree.getFailedTransfersRootHash(List.empty))
    failedTransfersHash shouldBe ""
  }

  "getFailedTransfersRootHash, 1 index" in {
    val failedTransfersHash = Base64.encode(BridgeMerkleTree.getFailedTransfersRootHash(List(1)))
    failedTransfersHash shouldBe "6wti5laigNSEWvM8kfJjSdt90kLQMMCwb59lJTJnFXU="
  }

  "getFailedTransfersRootHash, 2 indexes" in {
    val failedTransfersHash = Base64.encode(BridgeMerkleTree.getFailedTransfersRootHash(List(1, 2)))
    failedTransfersHash shouldBe "O4J9Z0+sQmyTvf2teltyZzTOkT9NkKlJWbRWs6AT1Bs="
  }

  "getFailedTransfersRootHash, 3 indexes" in {
    val failedTransfersHash = Base64.encode(BridgeMerkleTree.getFailedTransfersRootHash(List(1, 2, 3)))
    failedTransfersHash shouldBe "HfIfxbQd7rf0YiFX99DQz8K6Y0Hnd5DvWFFawYhvXXc="
  }

  "getFailedTransfersRootHash, 4 indexes" in {
    val failedTransfersHash = Base64.encode(BridgeMerkleTree.getFailedTransfersRootHash(List(1, 2, 3, 4)))
    failedTransfersHash shouldBe "bKsOAvL+iFcLAHTgq2HHjXqeBtxz3WUn2QnHkulYXf8="
  }
}
