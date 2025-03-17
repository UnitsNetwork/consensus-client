#!/usr/bin/env python
import os
import subprocess
from decimal import Decimal
from time import sleep

from units_network import units, waves
from units_network.common_utils import configure_cli_logger
from web3 import Web3

from local.network import get_local
from local.node import Node

log = configure_cli_logger(__file__)
network = get_local()
contracts_dir = os.getenv("CONTRACTS_DIR") or os.path.join(
    os.getcwd(), "..", "..", "contracts"
)

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

key = f"unit_{network.cl_chain_contract.oracleAddress}_approved"
if len(network.cl_registry.getData(regex=key)) == 0:
    log.info("Approve chain contract on registry")
    network.cl_registry.storeData(key, "boolean", True)

script_info = network.cl_chain_contract.oracleAcc.scriptInfo()
if script_info["script"] is None:
    log.info("Set chain contract script")

    with open(
        os.path.join(contracts_dir, "waves", "src", "main.ride"), "r", encoding="utf-8"
    ) as file:
        source = file.read()
    r = network.cl_chain_contract.setScript(source)
    waves.force_success(log, r, "Can not set the chain contract script")

if not network.cl_chain_contract.isContractSetup():
    log.info("Call ChainContract.setup")
    el_genesis_block = network.w3.eth.get_block(0)

    assert "hash" in el_genesis_block
    el_genesis_block_hash = el_genesis_block["hash"]

    log.info(f"Genesis block hash: {el_genesis_block_hash.to_0x_hex()}")

    r = network.cl_chain_contract.setup(
        el_genesis_block_hash, daoAddress=network.cl_dao.address
    )
    waves.force_success(log, r, "Can not setup the chain contract")

cl_token = network.cl_chain_contract.getNativeToken()
cl_poor_accounts = []
for cl_account in network.cl_rich_accounts:
    if cl_account.balance(cl_token.assetId) <= 0:
        cl_poor_accounts.append(cl_account)

cl_poor_accounts_number = len(cl_poor_accounts)
txn_ids = []
if cl_poor_accounts_number > 0:
    user_amount_for_each = Decimal(100)
    atomic_amount_for_each = units.user_to_atomic(
        user_amount_for_each, cl_token.decimals
    )
    log.info(
        f"Issue {user_amount_for_each * cl_poor_accounts_number} UNIT0 additional tokens for testing purposes"
    )
    reissue_txn_id = network.cl_chain_contract.oracleAcc.reissueAsset(
        cl_token, atomic_amount_for_each * cl_poor_accounts_number, reissuable=True
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

for txn_id in txn_ids:
    waves.wait_for(txn_id)
    log.info(f"Distrubute UNIT0 tokens transaction {txn_id} confirmed")

r = network.cl_chain_contract.evaluate("allMiners")
joined_miners = []
for entry in r["result"]["value"]:
    joined_miners.append(entry["value"])
log.info(f"Miners: {joined_miners}")

txn_ids = []
for miner in network.cl_miners:
    if miner.account.address not in joined_miners:
        log.info(f"Call ChainContract.join by miner f{miner.account.address}")
        r = network.cl_chain_contract.join(miner.account, miner.el_reward_address)
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
    log.info(f"Join transaction {txn_id} confirmed")

while True:
    r = network.w3.eth.get_block("latest")
    if "number" in r and r["number"] >= 1:
        break
    log.info("Wait for at least one block on EL")
    sleep(3)

log.info("Deploying the StandardBridge contract")

# TODO: check if deployment is required
try:
    cmd = (
        f"forge script --force -vvvv scripts/Deployer.s.sol:Deployer --private-key {network.el_deployer_private_key} "
        + f"--fork-url {network.settings.el_node_api_url} --broadcast"
    )

    retcode = subprocess.call(cmd, shell=True, cwd=os.path.join(contracts_dir, "eth"))
    if retcode < 0:
        log.error(f"Child was terminated by signal: {-retcode}")
        exit(1)
    else:
        log.info(f"Child returned: {retcode}")
except OSError as e:
    log.error(f"Execution failed: {e}")
    exit(1)

key = "assetTransfersActivationEpoch"
if len(network.cl_chain_contract.getData(regex="assetTransfersActivationEpoch")) == 0:
    enable_transfers_txn = network.cl_chain_contract.oracleAcc.invokeScript(
        dappAddress=network.cl_chain_contract.oracleAddress,
        functionName="enableTokenTransfers",
        params=[
            {"type": "string", "value": network.el_standard_bridge_address},
            {"type": "string", "value": network.el_wwaves_address},
            {"type": "integer", "value": 5},
        ],
    )
    waves.force_success(
        log,
        enable_transfers_txn,
        "Could not enable token transfers",
        wait=True,
    )

log.info(f"StandardBridge address: {network.bridges.standard_bridge.contract_address}")
log.info(f"ERC20 token address: {network.el_test_erc20.contract_address}")
log.info(
    f"Test CL asset id: {network.cl_test_asset.assetId}"
)  # Issues and registers asset under the hood

attempts = 10
while attempts > 0:
    ratio = network.bridges.standard_bridge.token_ratio(
        network.el_test_erc20.contract_address
    )
    if ratio > 0:
        log.info(f"Token registered, ratio: {ratio}")
        break
    sleep(3)
    attempts -= 1

if attempts <= 0:
    log.error("Can't register test ERC20")
    exit(1)

log.info("Done")
