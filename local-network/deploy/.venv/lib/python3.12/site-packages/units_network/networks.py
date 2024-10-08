import logging
import os
from functools import cached_property

import web3.exceptions
from hexbytes import HexBytes
from pywaves import pw
from web3 import Web3

import units_network.exceptions
from units_network.bridge import Bridge
from units_network.chain_contract import ChainContract, ContractBlock


class NetworkSettings:
    def __init__(
        self,
        name: str,
        chain_id_str: str,
        cl_node_api_url: str,
        el_node_api_url: str,
        chain_contract_address: str,
    ):
        self.name = name
        self.chain_id_str = chain_id_str
        self.chain_id = ord(chain_id_str)
        self.cl_node_api_url = cl_node_api_url
        self.el_node_api_url = el_node_api_url
        self.chain_contract_address = chain_contract_address


# Creating instances for each network
stage_net = NetworkSettings(
    name="StageNet",
    chain_id_str="S",
    cl_node_api_url="https://nodes-stagenet.wavesnodes.com",
    el_node_api_url="https://rpc-stagenet.unit0.dev",
    chain_contract_address="3Mew9817x6rePmCUKNAiRxuzNEP8F2XK1Kd",
)

test_net = NetworkSettings(
    name="TestNet",
    chain_id_str="T",
    cl_node_api_url="https://nodes-testnet.wavesnodes.com",
    el_node_api_url="https://rpc-testnet.unit0.dev",
    chain_contract_address="3Msx4Aq69zWUKy4d1wyKnQ4ofzEDAfv5Ngf",
)

networks = {n.chain_id_str: n for n in [stage_net, test_net]}


def get_network_settings(chain_id_str: str) -> NetworkSettings:
    r = networks.get(chain_id_str)
    if not r:
        raise ValueError(f"Unknown network {chain_id_str}")

    return r


class Network:
    def __init__(self, settings: NetworkSettings):
        self.settings = settings

    @cached_property
    def w3(self) -> Web3:
        return Web3(Web3.HTTPProvider(self.settings.el_node_api_url))

    @cached_property
    def cl_chain_contract(self) -> ChainContract:
        return ChainContract(oracleAddress=self.settings.chain_contract_address)

    @cached_property
    def el_bridge(self) -> Bridge:
        return Bridge(self.w3, self.cl_chain_contract.getElBridgeAddress())

    def require_settled_block(
        self, block_hash: HexBytes, block_number: int
    ) -> ContractBlock:
        block_hash_hex = block_hash.hex()
        while True:
            try:
                return self.cl_chain_contract.waitForBlock(block_hash_hex)
            except units_network.exceptions.TimeExhausted:
                pass

            self.check_block_presence(block_hash, block_number)

    def require_finalized_block(self, block: ContractBlock):
        while True:
            try:
                self.cl_chain_contract.waitForFinalized(block)
                return
            except units_network.exceptions.TimeExhausted:
                pass

            self.check_block_presence(block.hash, block.chain_height)

    def check_block_presence(self, block_hash: HexBytes, block_number: int):
        try:
            expected_block = self.w3.eth.get_block(block_hash)
            assert "hash" in expected_block

            actual_block = self.w3.eth.get_block(block_number)
            assert "hash" in actual_block

            if actual_block["hash"] != expected_block["hash"]:
                raise units_network.exceptions.BlockDisappeared(block_hash.hex())
        except web3.exceptions.BlockNotFound:
            raise units_network.exceptions.BlockDisappeared(block_hash.hex())


def select(chain_id_str: str):
    s = get_network_settings(chain_id_str)
    return create_manual(s)


def create_manual(settings: NetworkSettings) -> Network:
    prepare(settings)
    return Network(settings)


def prepare(settings: NetworkSettings) -> None:
    log = logging.getLogger(os.path.basename(__file__))
    log.info(f"Selected {settings.name} ({settings.chain_id_str})")
    pw.setNode(settings.cl_node_api_url, settings.name, settings.chain_id_str)
