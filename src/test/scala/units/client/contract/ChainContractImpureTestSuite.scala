package units.client.contract

import com.wavesplatform.transaction.TxHelpers
import units.BaseIntegrationTestSuite
import units.eth.EthAddress

class ChainContractImpureTestSuite extends BaseIntegrationTestSuite {
  private val bridgeAddress = EthAddress.from("0x00000000000000000000000000000155C3d06a7E").value

  "registerToken" - {
    "Unknown token" in withExtensionDomain() { d =>
      val issueTxn    = TxHelpers.issue(d.chainRegistryAccount, 1, 8)
      val registerTxn = d.ChainContract.registerToken(issueTxn.asset, bridgeAddress)
      d.appendMicroBlockE(registerTxn).left.value.getMessage should include(s"Unknown asset ${issueTxn.asset}")
    }

    "Invalid ERC20 address" in forAll(
      Table(
        "address",
        "",
        "foo",
        "000000000000000000000000000000000155C3d06a7E"
      )
    ) { address =>
      withExtensionDomain() { d =>
        val issueTxn    = TxHelpers.issue(d.chainRegistryAccount, 1, 8)
        val registerTxn = d.ChainContract.registerToken(issueTxn.asset, address, d.chainContractAccount)
        d.appendMicroBlockE(issueTxn, registerTxn).left.value.getMessage should include(s"Invalid ERC20 address: $address")
      }
    }

    "Registers a token" in withExtensionDomain() { d =>
      d.chainContractClient.getIssuedTokenRegistrySize shouldBe 0

      val issueTxn = TxHelpers.issue(d.chainRegistryAccount, 1, 8)
      d.appendMicroBlock(issueTxn, d.ChainContract.registerToken(issueTxn.asset, bridgeAddress))

      d.chainContractClient.getIssuedTokenRegistrySize shouldBe 1
    }
  }

  "createAndRegisterToken" - {
    "Invalid sender" in withExtensionDomain() { d =>
      val txn = d.ChainContract.createAndRegisterToken(bridgeAddress, "test", "test", 8, d.chainRegistryAccount)
      d.appendMicroBlockE(txn).left.value.getMessage should include("Only owner of chain contract can do this")
    }

    "Invalid ERC20 address" in forAll(
      Table(
        "address",
        "",
        "foo",
        "000000000000000000000000000000000155C3d06a7E"
      )
    ) { address =>
      withExtensionDomain() { d =>
        val txn = d.ChainContract.createAndRegisterToken(address, "test", "test", 8, d.chainContractAccount)
        d.appendMicroBlockE(txn).left.value.getMessage should include(s"Invalid ERC20 address: $address")
      }
    }

    "Registers a token" in withExtensionDomain() { d =>
      d.chainContractClient.getIssuedTokenRegistrySize shouldBe 0

      val txn = d.ChainContract.createAndRegisterToken(bridgeAddress, "test", "test", 8)
      d.appendMicroBlock(txn)

      d.chainContractClient.getIssuedTokenRegistrySize shouldBe 1
    }
  }
}
