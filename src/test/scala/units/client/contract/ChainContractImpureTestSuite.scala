package units.client.contract

import com.wavesplatform.transaction.{Asset, TxHelpers}
import units.eth.EthAddress
import units.{BaseIntegrationTestSuite, ExtensionDomain}

class ChainContractImpureTestSuite extends BaseIntegrationTestSuite {
  "registerAsset" - {
    "Unknown asset" in withExtensionDomain() { d =>
      val issueTxn    = TxHelpers.issue(d.chainRegistryAccount, 1, 8)
      val registerTxn = d.ChainContract.registerAsset(issueTxn.asset, elStandardBridgeAddress, 8)
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
        val registerTxn = d.ChainContract.registerAsset(issueTxn.asset, address, 8, d.chainContractAccount)
        d.appendMicroBlockE(issueTxn, registerTxn).left.value.getMessage should include(s"Invalid ERC20 address: $address")
      }
    }

    "Can't register a EL asset twice" in withExtensionDomain() { d =>
      val issueTxn1 = TxHelpers.issue(d.chainRegistryAccount, 1, 8)
      val issueTxn2 = TxHelpers.issue(d.chainRegistryAccount, 1, 8)
      d.appendMicroBlock(issueTxn1, issueTxn2)

      val txn1 = d.ChainContract.registerAsset(issueTxn1.asset, elStandardBridgeAddress, 8)
      d.appendMicroBlock(txn1)

      val txn2 = d.ChainContract.registerAsset(issueTxn2.asset, elStandardBridgeAddress, 8)
      d.appendMicroBlockE(txn2).left.value.getMessage should include(s"EL asset is already registered: ${elStandardBridgeAddress.hexNoPrefix}")
    }

    "Can't register a CL asset twice" - {
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
        val txn1 = d.ChainContract.registerAsset(asset, elStandardBridgeAddress, 8)
        d.appendMicroBlock(txn1)

        val txn2 = d.ChainContract.registerAsset(asset, EthAddress.from("0x10000000000000000000000000000155C3d06a7E").value, 8)
        val name = asset.fold(ChainContractClient.Registry.WavesAssetName)(_.id.toString)
        d.appendMicroBlockE(txn2).left.value.getMessage should include(s"CL asset is already registered: $name")
      }
    }

    "Registers an asset" in withExtensionDomain() { d =>
      d.chainContractClient.getAssetRegistrySize shouldBe 0

      val issueTxn = TxHelpers.issue(d.chainRegistryAccount, 1, 8)
      d.appendMicroBlock(issueTxn, d.ChainContract.registerAsset(issueTxn.asset, elStandardBridgeAddress, 8))

      d.chainContractClient.getAssetRegistrySize shouldBe 1
    }
  }

  "createAndRegisterAsset" - {
    "Invalid sender" in withExtensionDomain() { d =>
      val txn = d.ChainContract.createAndRegisterAsset(elStandardBridgeAddress, 8, "test", "test", 8, d.chainRegistryAccount)
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
        val txn = d.ChainContract.createAndRegisterAsset(address, 8, "test", "test", 8, d.chainContractAccount)
        d.appendMicroBlockE(txn).left.value.getMessage should include(s"Invalid ERC20 address: $address")
      }
    }

    "Can't register a EL asset twice" in withExtensionDomain() { d =>
      val txn1 = d.ChainContract.createAndRegisterAsset(elStandardBridgeAddress, 8, "test", "test", 8)
      d.appendMicroBlock(txn1)

      val txn2 = d.ChainContract.createAndRegisterAsset(elStandardBridgeAddress, 8, "test", "test", 8)
      d.appendMicroBlockE(txn2).left.value.getMessage should include(s"EL asset is already registered: ${elStandardBridgeAddress.hexNoPrefix}")
    }

    "Registers an asset" in withExtensionDomain() { d =>
      d.chainContractClient.getAssetRegistrySize shouldBe 0

      val txn = d.ChainContract.createAndRegisterAsset(elStandardBridgeAddress, 8, "test", "test", 8)
      d.appendMicroBlock(txn)

      d.chainContractClient.getAssetRegistrySize shouldBe 1
    }
  }
}
