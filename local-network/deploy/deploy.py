#!/usr/bin/env python
import os
from decimal import Decimal
from time import sleep
from subprocess import call
import sys, json

from units_network import units, waves
from units_network.common_utils import configure_cli_logger
from web3 import Web3

from local.ContractFactory import ContractFactory
from local.network import get_local
from local.node import Node

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

key = f"unit_{network.cl_chain_contract.oracleAddress}_approved"
if len(network.cl_registry.getData(regex=key)) == 0:
    log.info("Approve chain contract on registry")
    network.cl_registry.storeData(key, "boolean", True)

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
try:
    retcode = call("/root/.foundry/bin/forge script --force -vvvv scripts/Deployer.s.sol:Deployer --private-key $PRIVATE_KEY --fork-url http://ec-1:8545 --broadcast",
        shell=True,
        cwd='/tmp/contracts'
    )
    if retcode < 0:
        print("Child was terminated by signal", -retcode, file=sys.stderr)
    else:
        print("Child returned", retcode, file=sys.stderr)
except OSError as e:
    print("Execution failed:", e, file=sys.stderr)

with open('/tmp/contracts/target/deployments/1337/.deploy', 'r') as deployFile:
    deployments = json.load(deployFile)
    standard_bridge_address = deployments['StandardBridge']
    log.info(f'StandardBridge address: {standard_bridge_address}')
    wwaves_address = deployments['WWaves']
    log.info(f'WWaves address: {wwaves_address}')
    r = network.cl_chain_contract.oracleAcc.invokeScript(
        dappAddress=network.cl_chain_contract.oracleAddress,
        functionName='enableTokenTransfers',
        params=[
            {
                'type': 'string',
                'value': standard_bridge_address
            },
            {
                'type': 'string',
                'value': wwaves_address
            },
            {
                'type': 'integer',
                'value': 5
            },
        ]
    )
    waves.force_success(
        log,
        r,
        'could not enable token transfers',
        wait=False,
    )
    txn_id = r["id"]  # type: ignore
    log.info(f"Transaction id: {txn_id}")  # type: ignore
    waves.wait_for(txn_id)

# OLD:
# ContractFactory.maybe_deploy(
#     network.w3,
#     network.el_rich_accounts[0],
#     os.getcwd() + "/setup/el/StandardBridge.json",
# )
#
#
# log.info("Deploying the test ERC20 contract")
# ContractFactory.maybe_deploy(
#     network.w3,
#     network.el_rich_accounts[1],
#     os.getcwd() + "/setup/el/TERC20.json",
# )

key = "elStandardBridgeAddress"
standard_bridge = network.bridges.standard_bridge
curr_standard_bridge_address = network.cl_chain_contract.getData(key)
expected_standard_bridge_address = standard_bridge.contract_address.lower()
if curr_standard_bridge_address != expected_standard_bridge_address:
    log.info(
        f"Set bridge address in ChainContract to {expected_standard_bridge_address}, current: {curr_standard_bridge_address}"
    )
    update_txn = network.cl_chain_contract.storeData(
        key, "string", expected_standard_bridge_address
    )
    waves.force_success(log, update_txn, f"Can not change ChainContract.{key}")

log.info(f"StandardBridge address: {standard_bridge.contract_address}")
log.info(f"ERC20 token address: {network.el_test_erc20.contract_address}")
log.info(f"Test CL asset id: {network.cl_test_asset.assetId}")

attempts = 10
while attempts > 0:
    ratio = standard_bridge.token_ratio(
        Web3.to_checksum_address(network.el_test_erc20.contract_address)
    )
    if ratio > 0:
        log.info(f"Token registered, ratio: {ratio}")
        break
    sleep(3)
    attempts -= 1

log.info("Done")
