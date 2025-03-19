import logging
from dataclasses import dataclass
from functools import cached_property
from typing import List, Optional

from eth_account.signers.local import LocalAccount
from eth_typing import ChecksumAddress, HexStr
from pywaves import Asset, pw
from units_network import networks, waves
from units_network.chain_contract import ChainContract
from units_network.erc20 import Erc20
from units_network.networks import Network, NetworkSettings
from web3 import Account, Web3

from local.common import in_docker


def get_waves_api_url(n: int) -> str:
    return f"http://waves-node-{n}:6869" if in_docker() else f"http://127.0.0.1:{n}6869"


def get_ec_api_url(n: int) -> str:
    return f"http://ec-{n}:8545" if in_docker() else f"http://127.0.0.1:{n}8545"


@dataclass
class Miner:
    account: pw.Address
    el_reward_address: ChecksumAddress

    @classmethod
    def new(cls, cl_seed: str, el_reward_address: str, cl_nonce: int = 0) -> "Miner":
        return cls(
            account=pw.Address(seed=cl_seed, nonce=cl_nonce),
            el_reward_address=Web3.to_checksum_address(
                Web3.to_hex(hexstr=HexStr(el_reward_address))
            ),
        )


class ExtendedNetwork(Network):
    def __init__(self, settings: NetworkSettings):
        super().__init__(settings)
        self.log = logging.getLogger("ExtendedNetwork")

    @cached_property
    def cl_dao(self) -> pw.Address:
        return pw.Address(seed="devnet dao", nonce=0)

    @cached_property
    def cl_registry(self) -> pw.Oracle:
        return pw.Oracle(seed="devnet registry")

    @cached_property
    def cl_chain_contract(self) -> ChainContract:
        return ChainContract(seed="devnet cc", nonce=0)

    @cached_property
    def cl_test_asset(self) -> Asset:
        test_asset_name = "TestToken"

        asset = self.cl_chain_contract.findRegisteredAsset(test_asset_name)
        if asset:
            return asset

        self.log.info(f"Registering {test_asset_name}")
        register_txn = self.cl_chain_contract.issueAndRegister(
            sender=self.cl_chain_contract.oracleAcc,
            erc20Address=self.el_test_erc20.contract_address,
            elDecimals=self.el_test_erc20.decimals,
            name=test_asset_name,
            description="Test bridged token",
            clDecimals=8,
            txFee=100_500_000,
        )
        waves.force_success(self.log, register_txn, "Can not register asset")

        asset = self.cl_chain_contract.findRegisteredAsset(test_asset_name)
        if asset:
            return asset

        raise Exception("Can't deploy a custom CL asset")

    @cached_property
    def cl_miners(self) -> List[Miner]:
        return [
            Miner.new("devnet-1", "0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f9"),
            # TODO: Until fixed an issue with rollback
            # Miner(
            #     account=pw.Address(
            #         seed="devnet-2",
            #         nonce=0,
            #     ),
            #     el_reward_address_hex=HexStr(
            #         "0xcf0b9e13fdd593f4ca26d36afcaa44dd3fdccbed"
            #     ),
            # ),
            # Miner(
            #     account=pw.Address(
            #         seed="devnet-3",
            #         nonce=0,
            #     ),
            #     el_reward_address_hex=HexStr(
            #         "0xf1FE6d7bfebead68A8C06cCcee97B61d7DAA0338"
            #     ),
            # ),
            # Miner(
            #     account=pw.Address(
            #         seed="devnet-4",
            #         nonce=0,
            #     ),
            #     el_reward_address_hex=HexStr(
            #         "0x10eDdE5dc07eF63E6bb7018758e6fcB5320d8cAa"
            #     ),
            # ),
        ]

    @cached_property
    def cl_rich_accounts(self) -> List[pw.Address]:
        return [pw.Address(seed="devnet rich", nonce=n) for n in range(0, 2)]

    @cached_property
    def el_rich_accounts(self) -> List[LocalAccount]:
        return [
            Account.from_key(
                "0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63"
            ),
            Account.from_key(
                "0xae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f"
            ),
        ]

    @cached_property
    def el_deployer_private_key(self) -> str:
        return "8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63"

    @cached_property
    def el_standard_bridge_address(self) -> ChecksumAddress:
        return Web3.to_checksum_address("0x9a3DBCa554e9f6b9257aAa24010DA8377C57c17e")

    @cached_property
    def el_wwaves_address(self) -> ChecksumAddress:
        return Web3.to_checksum_address("0x2E1f232a9439C3D459FcEca0BeEf13acc8259Dd8")

    @cached_property
    def el_test_erc20_address(self) -> ChecksumAddress:
        return Web3.to_checksum_address("0x9B8397f1B0FEcD3a1a40CdD5E8221Fa461898517")

    @cached_property
    def el_test_erc20(self) -> Erc20:
        return self.get_erc20(self.el_test_erc20_address)



local_net = NetworkSettings(
    name="LocalNet",
    cl_chain_id_str="D",
    cl_node_api_url=get_waves_api_url(1),
    el_node_api_url=get_ec_api_url(1),
    chain_contract_address="3FXDd4LoxxqVLfMk8M25f8CQvfCtGMyiXV1",
)


_NETWORK: Optional[ExtendedNetwork] = None


def get_local() -> ExtendedNetwork:
    global _NETWORK
    if _NETWORK is None:
        networks.prepare(local_net)
        _NETWORK = ExtendedNetwork(local_net)

    return _NETWORK
