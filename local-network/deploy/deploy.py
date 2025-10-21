#!/usr/bin/env python
import os
import subprocess
from decimal import Decimal
from time import sleep

from pywaves import pw
from units_network import units, waves
from units_network.common_utils import configure_cli_logger

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
    r = network.cl_chain_contract.setScript(source, txFee=7_000_000)
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
        cl_token,
        atomic_amount_for_each * cl_poor_accounts_number,
        reissuable=True,
        txFee=500_000,
    )
    waves.wait_for_approval(log, reissue_txn_id)
    log.info("Additional tokens issued")

    for cl_account in cl_poor_accounts:
        log.info(f"Send {user_amount_for_each} UNIT0 tokens to {cl_account.address}")
        txn_result = network.cl_chain_contract.oracleAcc.sendAsset(
            recipient=cl_account,
            asset=cl_token,
            amount=atomic_amount_for_each,
            txFee=500_000,
        )
        waves.force_success(log, txn_result, "Can not send UNIT0 tokens", wait=False)
        txn_id = txn_result["id"]  # type: ignore
        log.info(f"Transaction id: {txn_id}")
        txn_ids.append(txn_id)

for txn_id in txn_ids:
    waves.wait_for_approval(log, txn_id)
    log.info(f"Distribute UNIT0 tokens transaction {txn_id} confirmed")

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
    waves.wait_for_approval(log, txn_id)
    log.info(f"Join transaction {txn_id} confirmed")

while True:
    r = network.w3.eth.get_block("latest")
    if "number" in r and r["number"] >= 1:
        break
    log.info("Wait for at least one block on EL")
    sleep(3)

log.info("Deploying Solidity contracts")

# TODO: check if deployment is required
try:
    # Use IT.s.sol for now, because we need a test token. If update to other script, make sure you changed keys in network.py
    cmd = (
        f"forge script --force -vvvv {contracts_dir}/eth/scripts/IT.s.sol:IT --private-key {network.el_deployer.key.hex()} "
        + f"--fork-url {network.settings.el_node_api_url} --broadcast"
    )

    env = os.environ.copy()
    # env["BRIDGE_PROXY_ADMIN"] = network.el_deployer.address
    retcode = subprocess.call(
        cmd,
        shell=True,
        cwd=os.path.join(contracts_dir, "eth"),
        env=env,
    )
    if retcode < 0:
        log.error(f"Child was terminated by signal: {-retcode}")
        exit(1)
    else:
        log.info(f"Child returned: {retcode}")
except OSError as e:
    log.error(f"Execution failed: {e}")
    exit(1)

features_to_activate = []

key_asset_transfers = "assetTransfersActivationEpoch"
if len(network.cl_chain_contract.getData(regex=key_asset_transfers)) == 0:
    features_to_activate.append("asset_transfers")

key_strict_transfers = "strictC2ETransfersActivationEpoch"
if len(network.cl_chain_contract.getData(regex=key_strict_transfers)) == 0:
    features_to_activate.append("strict_transfers")

if features_to_activate:
    activation_height = pw.height() + 2
    if "asset_transfers" in features_to_activate:
        log.info("Activating asset transfers...")
        enable_transfers_txn = network.cl_chain_contract.oracleAcc.invokeScript(
            dappAddress=network.cl_chain_contract.oracleAddress,
            functionName="enableTokenTransfers",
            params=[
                {"type": "string", "value": network.el_standard_bridge_address},
                {"type": "string", "value": network.el_wwaves_address},
                {"type": "integer", "value": activation_height},
            ],
            txFee=900_000,
        )
        waves.force_success(
            log,
            enable_transfers_txn,
            "Could not enable token transfers",
            wait=True,
        )

    if "strict_transfers" in features_to_activate:
        strict_transfers_txn = network.cl_chain_contract.oracleAcc.dataTransaction(
            [
                {
                    "type": "integer",
                    "key": "strictC2ETransfersActivationEpoch",
                    "value": activation_height,
                }
            ]
        )
        waves.force_success(
            log,
            strict_transfers_txn,
            "Could not enable strict transfers",
            wait=True,
        )

    log.info("Waiting for activation height...")
    while pw.height() < activation_height:
        sleep(3)

log.info(f"StandardBridge address: {network.bridges.standard_bridge.contract_address}")
log.info(f"ERC20 token address: {network.el_test_erc20.contract_address}")
# Issues and registers asset under the hood
log.info(f"Test CL asset id: {network.cl_test_asset.assetId}")

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
