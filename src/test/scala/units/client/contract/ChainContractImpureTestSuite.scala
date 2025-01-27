package units.client.contract

import com.wavesplatform.transaction.{Asset, TxHelpers}
import units.eth.EthAddress
import units.{BaseIntegrationTestSuite, ExtensionDomain}

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

    "Can't register a EL token twice" in withExtensionDomain() { d =>
      val issueTxn1 = TxHelpers.issue(d.chainRegistryAccount, 1, 8)
      val issueTxn2 = TxHelpers.issue(d.chainRegistryAccount, 1, 8)
      d.appendMicroBlock(issueTxn1, issueTxn2)

      val txn1 = d.ChainContract.registerToken(issueTxn1.asset, bridgeAddress)
      d.appendMicroBlock(txn1)

      val txn2 = d.ChainContract.registerToken(issueTxn2.asset, bridgeAddress)
      d.appendMicroBlockE(txn2).left.value.getMessage should include(s"EL asset is already registered: ${bridgeAddress.hexNoPrefix}")
    }

    "Can't register a CL token twice" - {
      "WAVES" in withExtensionDomain() { d =>
        val issueTxn = TxHelpers.issue(d.chainRegistryAccount, 1, 8)
        d.appendMicroBlock(issueTxn)
        test(d, Asset.Waves)
      }

      "Issued" in withExtensionDomain() { d =>
        val issueTxn = TxHelpers.issue(d.chainRegistryAccount, 1, 8)
        d.appendMicroBlock(issueTxn)
        test(d, issueTxn.asset)
      }

      def test(d: ExtensionDomain, asset: Asset): Unit = {
        val txn1 = d.ChainContract.registerToken(asset, bridgeAddress)
        d.appendMicroBlock(txn1)

        val txn2 = d.ChainContract.registerToken(asset, EthAddress.from("0x10000000000000000000000000000155C3d06a7E").value)
        val name = asset.fold(ChainContractClient.Registry.WavesTokenName)(_.id.toString)
        d.appendMicroBlockE(txn2).left.value.getMessage should include(s"CL asset is already registered: $name")
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

    "Can't register a EL token twice" in withExtensionDomain() { d =>
      val txn1 = d.ChainContract.createAndRegisterToken(bridgeAddress, "test", "test", 8)
      d.appendMicroBlock(txn1)

      val txn2 = d.ChainContract.createAndRegisterToken(bridgeAddress, "test", "test", 8)
      d.appendMicroBlockE(txn2).left.value.getMessage should include(s"EL asset is already registered: ${bridgeAddress.hexNoPrefix}")
    }

    "Registers a token" in withExtensionDomain() { d =>
      d.chainContractClient.getIssuedTokenRegistrySize shouldBe 0

      val txn = d.ChainContract.createAndRegisterToken(bridgeAddress, "test", "test", 8)
      d.appendMicroBlock(txn)

      d.chainContractClient.getIssuedTokenRegistrySize shouldBe 1
    }
  }
}
