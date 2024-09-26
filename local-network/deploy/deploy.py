from time import sleep

import requests
from pywaves import pw

from local.common import configure_script_logger
from local.network import get_network

log = configure_script_logger("main")
n = get_network()

from local import waves_txs
from local.accounts import accounts


# TODO: Move
class Node(object):
    def __init__(
        self,
        pw=pw,
    ):
        self.pw = pw

    def connected_peers(self):
        url = f"{self.pw.NODE}/peers/connected"
        response = requests.get(url)
        if response.status_code == 200:
            return response.json()["peers"]
        else:
            raise Exception(f"Error: {response.status_code}, {response.text}")


node = Node(pw)
min_peers = len(accounts.waves_miners) - 1
while True:
    r = node.connected_peers()
    if len(r) >= min_peers:
        break

    log.info(f"Wait for {min_peers} peers, now: {r}")
    sleep(2)

script_info = accounts.chain_contract.scriptInfo()
if script_info["script"] is None:
    log.info(f"Set chain contract script on {accounts.chain_contract.address}")

    with open("setup/waves/main.ride", "r", encoding="utf-8") as file:
        source = file.read()
    r = waves_txs.cc_set_script(accounts.chain_contract, source)
    waves_txs.force_success(log, r, "Can not set the chain contract script")

if not n.cl_chain_contract.isContractSetup():
    log.info(f"Setup chain contract on {accounts.chain_contract.address}")
    el_genesis_block = n.w3.eth.get_block(0)

    assert "hash" in el_genesis_block
    el_genesis_block_hash = el_genesis_block["hash"].hex()

    log.info(f"Genesis block hash: 0x{el_genesis_block_hash}")

    r = waves_txs.cc_setup(accounts.chain_contract, el_genesis_block_hash)
    waves_txs.force_success(log, r, "Can not setup the chain contract")


r = n.cl_chain_contract.evaluate("allMiners")
joined_miners = []
for entry in r["result"]["value"]:
    joined_miners.append(entry["value"])
log.info(f"Miners: {joined_miners}")

for miner in accounts.waves_miners:
    if miner.account.address not in joined_miners:
        log.info(f"Miner f{miner.account.address} joins the chain contract")
        r = waves_txs.cc_join(
            accounts.chain_contract.address, miner.account, miner.el_reward_address_hex
        )
        waves_txs.force_success(
            log,
            r,
            f"{miner.account.address} can not join the chain contract",
            wait=False,
        )

while True:
    r = n.w3.eth.get_block("latest")
    if "number" in r and r["number"] >= 1:
        break
    log.info("Wait for at least one block on EL")
    sleep(3)
