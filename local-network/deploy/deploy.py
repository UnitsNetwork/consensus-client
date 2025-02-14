#!/usr/bin/env python
from decimal import Decimal
from time import sleep

from units_network import units, waves
from units_network.chain_contract import HexStr
from units_network.common_utils import configure_cli_logger
from units_network.node import Node

from local.network import get_local

log = configure_cli_logger(__file__)
network = get_local()


node = Node()
min_peers = len(network.cl_miners) - 1
while True:
    r = node.connected_peers()
    if len(r) >= min_peers:
        break

    log.info(f"Wait for {min_peers} peers, now: {r}")
    sleep(2)

log.info(f"Registry address: {network.cl_registry.oracleAddress}")
log.info(f"Chain contract address: {network.cl_chain_contract.oracleAddress}")

log.info("Approve chain contract on registry")
network.cl_registry.storeData(
    f"unit_{network.cl_chain_contract.oracleAddress}_approved", "boolean", True
)

script_info = network.cl_chain_contract.oracleAcc.scriptInfo()
if script_info["script"] is None:
    log.info("Set chain contract script")

    with open("setup/waves/main.ride", "r", encoding="utf-8") as file:
        source = file.read()
    r = network.cl_chain_contract.setScript(source, 4_700_000)
    waves.force_success(log, r, "Can not set the chain contract script")

if not network.cl_chain_contract.isContractSetup():
    log.info("Call ChainContract.setup")
    el_genesis_block = network.w3.eth.get_block(0)

    assert "hash" in el_genesis_block
    el_genesis_block_hash = HexStr(el_genesis_block["hash"].to_0x_hex())

    log.info(f"Genesis block hash: {el_genesis_block_hash}")

    r = network.cl_chain_contract.setup(
        el_genesis_block_hash, daoAddress=network.cl_dao.address
    )
    waves.force_success(log, r, "Can not setup the chain contract")

cl_token = network.cl_chain_contract.getToken()
cl_poor_accounts = []
for cl_account in network.cl_rich_accounts:
    if cl_account.balance(cl_token.assetId) <= 0:
        cl_poor_accounts.append(cl_account)

cl_poor_accounts_number = len(cl_poor_accounts)
txn_ids = []
if cl_poor_accounts_number > 0:
    user_amount_for_each = Decimal(100)
    atomic_amount_for_each = units.raw_to_waves_atomic(user_amount_for_each)
    quantity = units.raw_to_waves_atomic(user_amount_for_each * cl_poor_accounts_number)
    log.info(
        f"Issue {user_amount_for_each * cl_poor_accounts_number} UNIT0 additional tokens for testing purposes"
    )
    reissue_txn_id = network.cl_chain_contract.oracleAcc.reissueAsset(
        cl_token, quantity, reissuable=True
    )
    waves.wait_for(reissue_txn_id)

    for cl_account in cl_poor_accounts:
        log.info(f"Send {user_amount_for_each} UNIT0 tokens to {cl_account.address}")
        txn_result = network.cl_chain_contract.oracleAcc.sendAsset(
            recipient=cl_account,
            asset=cl_token,
            amount=atomic_amount_for_each,
        )
        waves.force_success(log, txn_result, "Can not send UNIT0 tokens", wait=False)
        txn_id = txn_result["id"]  # type: ignore
        log.info(f"Transaction id: {txn_id}")
        txn_ids.append(txn_id)

r = network.cl_chain_contract.evaluate("allMiners")
joined_miners = []
for entry in r["result"]["value"]:
    joined_miners.append(entry["value"])
log.info(f"Miners: {joined_miners}")

for miner in network.cl_miners:
    if miner.account.address not in joined_miners:
        log.info(f"Call ChainContract.join by miner f{miner.account.address}")
        r = network.cl_chain_contract.join_v2(
            miner.account, miner.el_reward_address_hex
        )
        waves.force_success(
            log,
            r,
            f"{miner.account.address} can not join the chain contract",
            wait=False,
        )
        txn_id = r["id"]  # type: ignore
        log.info(f"Transaction id: {txn_id}")  # type: ignore
        txn_ids.append(txn_id)

for txn_id in txn_ids:
    waves.wait_for(txn_id)
    log.info(f"{txn_id} confirmed")

while True:
    r = network.w3.eth.get_block("latest")
    if "number" in r and r["number"] >= 1:
        break
    log.info("Wait for at least one block on EL")
    sleep(3)
