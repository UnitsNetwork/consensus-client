# TODO move to classes

import sys
from logging import Logger
from time import sleep

from pywaves import pw
from units_network.common_utils import hex_to_base64


def force_success(log: Logger, r, text, wait=True, pw=pw):
    if not r or "error" in r:
        log.error(f"{text}: {r}")
        sys.exit(1)

    if wait:
        id = r["id"]
        wait_for_txn(id, pw)
        log.info(f"{id} confirmed")


def wait_for_txn(id, pw=pw):
    while True:
        tx = pw.tx(id)
        if "id" in tx:
            return tx
        sleep(2)


def clean_hex_prefix(hex: str) -> str:
    return hex[2:] if hex.startswith("0x") else hex


# cc - Chain contract
# 1. Deploy chain contract script
def cc_set_script(chain_contract: pw.Address, script: str):
    return chain_contract.setScript(script, txFee=3_200_000)


# 2. Setup chain contract
def cc_setup(chain_contract: pw.Address, el_genesis_block_hash_hex: str):
    return chain_contract.invokeScript(
        dappAddress=chain_contract.address,
        functionName="setup",
        params=[
            {"type": "string", "value": clean_hex_prefix(el_genesis_block_hash_hex)},
            {
                # minerRewardInGwei
                "type": "integer",
                # 2_000_000_000 Gwei = 2_000_000_000*10^9 Wei = 2*10^18 Wei = 2 UNIT0 for epoch,
                # see bridge.sol for conversion details
                "value": 2_000_000_000,
            },
        ],
        txFee=100_500_000,
    )


# 3. Join EL miners on CL
def cc_join(
    chain_contract_address: str, sender: pw.Address, el_reward_address_hex: str
):
    return sender.invokeScript(
        dappAddress=chain_contract_address,
        functionName="join",
        params=[
            {
                "type": "binary",
                "value": f"base64:{hex_to_base64(clean_hex_prefix(el_reward_address_hex))}",
            },
        ],
        txFee=500_000,
    )
