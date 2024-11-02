from dataclasses import dataclass
from functools import cached_property
from typing import List, Optional

from eth_account.signers.local import LocalAccount
from pywaves import pw
from units_network import networks
from units_network.chain_contract import ChainContract, HexStr
from units_network.networks import Network, NetworkSettings
from web3 import Account

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
    def cl_dao(self) -> ChainContract:
        return pw.Address(seed="devnet dao 1", nonce=0)

    @cached_property
    def cl_chain_contract(self) -> ChainContract:
        return ChainContract(seed="devnet cc 1", nonce=0)

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
            Miner(
                account=pw.Address(
                    seed="devnet-2",
                    nonce=0,
                ),
                el_reward_address_hex=HexStr(
                    "0xcf0b9e13fdd593f4ca26d36afcaa44dd3fdccbed"
                ),
            ),
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


local_net = NetworkSettings(
    name="LocalNet",
    chain_id_str="D",
    cl_node_api_url=get_waves_api_url(1),
    el_node_api_url=get_ec_api_url(1),
    chain_contract_address="3FZyX72BjuE6s5PMTVQN9mJTN4jEJto95nv",
)


_NETWORK: Optional[ExtendedNetwork] = None


def get_local() -> ExtendedNetwork:
    global _NETWORK
    if _NETWORK is None:
        networks.prepare(local_net)
        _NETWORK = ExtendedNetwork(local_net)

    return _NETWORK
