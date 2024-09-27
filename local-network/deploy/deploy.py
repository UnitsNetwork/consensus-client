#!/usr/bin/env python
from time import sleep

from units_network import waves
from units_network.chain_contract import HexStr
from units_network.node import Node

from local.common import configure_script_logger
from local.network import get_local

log = configure_script_logger("main")
network = get_local()


node = Node()
min_peers = len(network.cl_miners) - 1
while True:
    r = node.connected_peers()
    if len(r) >= min_peers:
        break

    log.info(f"Wait for {min_peers} peers, now: {r}")
    sleep(2)

log.info(f"Chain contract address: {network.cl_chain_contract.oracleAddress}")

script_info = network.cl_chain_contract.oracleAcc.scriptInfo()
if script_info["script"] is None:
    log.info("Set chain contract script")

    with open("setup/waves/main.ride", "r", encoding="utf-8") as file:
        source = file.read()
    r = network.cl_chain_contract.setScript(source)
    waves.force_success(log, r, "Can not set the chain contract script")

if not network.cl_chain_contract.isContractSetup():
    log.info("Call ChainContract.setup")
    el_genesis_block = network.w3.eth.get_block(0)

    assert "hash" in el_genesis_block
    el_genesis_block_hash = HexStr(el_genesis_block["hash"].to_0x_hex())

    log.info(f"Genesis block hash: {el_genesis_block_hash}")

    r = network.cl_chain_contract.setup(el_genesis_block_hash)
    waves.force_success(log, r, "Can not setup the chain contract")


r = network.cl_chain_contract.evaluate("allMiners")
joined_miners = []
for entry in r["result"]["value"]:
    joined_miners.append(entry["value"])
log.info(f"Miners: {joined_miners}")

for miner in network.cl_miners:
    if miner.account.address not in joined_miners:
        log.info(f"Call ChainContract.join by miner f{miner.account.address}")
        r = network.cl_chain_contract.join(miner.account, miner.el_reward_address_hex)
        waves.force_success(
            log,
            r,
            f"{miner.account.address} can not join the chain contract",
            wait=False,
        )

while True:
    r = network.w3.eth.get_block("latest")
    if "number" in r and r["number"] >= 1:
        break
    log.info("Wait for at least one block on EL")
    sleep(3)
