from dataclasses import dataclass
from functools import cached_property
import logging
from typing import List, Optional
import os

from eth_account.signers.local import LocalAccount
from pywaves import Address, Asset, pw
from units_network import networks, waves
from units_network.chain_contract import ChainContract, HexStr
from units_network.networks import Network, NetworkSettings
from web3 import Account

from local.ContractFactory import ContractFactory
from local.Erc20 import Erc20
from local.StandardBridge import StandardBridge
from local.common import in_docker


def get_waves_api_url(n: int) -> str:
    return f"http://wavesnode-{n}:6869" if in_docker() else f"http://127.0.0.1:{n}6869"


def get_ec_api_url(n: int) -> str:
    return f"http://ec-{n}:8545" if in_docker() else f"http://127.0.0.1:{n}8545"


@dataclass
class Miner:
    account: pw.Address
    el_reward_address_hex: HexStr


class ExtendedNetwork(Network):
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
        log = logging.getLogger("ExtendedNetwork")
        test_asset_name = "Test TTK token"

        cl_issuer_assets = self.cl_chain_contract_registered_assets()
        for asset in cl_issuer_assets:
            if asset.name.decode("ascii") == test_asset_name:
                return asset

        register_txn = self.cl_chain_contract.createAndRegisterAsset(
            erc20Address=self.el_test_erc20.contract_address,
            elDecimals=self.el_test_erc20.decimals,
            name=test_asset_name,
            description="Test bridged token",
            clDecimals=8,
            txFee=100_500_000,
        )

        waves.force_success(log, register_txn, "Can not register asset")

        # TODO: fix dup
        cl_issuer_assets = self.cl_chain_contract_registered_assets()
        for asset in cl_issuer_assets:
            if asset.name.decode("ascii") == test_asset_name:
                return asset

        raise Exception("Can't deploy a custom CL asset")

    # TODO: Move
    def cl_chain_contract_registered_assets(self):
        xs = self.cl_chain_contract.getData(regex="assetRegistryIndex_.*")
        return [Asset(x["value"]) for x in xs]

    @cached_property
    def cl_miners(self) -> List[Miner]:
        return [
            Miner(
                account=pw.Address(
                    seed="devnet-1",
                    nonce=0,
                ),
                el_reward_address_hex=HexStr(
                    "0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f9"
                ),
            ),
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
    def el_standard_bridge(self) -> StandardBridge:
        return ContractFactory.connect_standard_bridge(
            self.w3,
            self.el_rich_accounts[0],
            os.getcwd() + "/setup/el/StandardBridge.json",
        )

    @cached_property
    def el_test_erc20(self) -> Erc20:
        return ContractFactory.connect_erc20(
            self.w3,
            self.el_rich_accounts[1],
            os.getcwd() + "/setup/el/TERC20.json",
        )


local_net = NetworkSettings(
    name="LocalNet",
    chain_id_str="D",
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
