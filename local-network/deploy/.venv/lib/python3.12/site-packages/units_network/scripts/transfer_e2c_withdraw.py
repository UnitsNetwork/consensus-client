#!/usr/bin/env python
import json
import sys

import pywaves as pw
from hexbytes import HexBytes
from web3 import Web3
from web3.types import TxData

from units_network import common_utils, networks


def main():
    log = common_utils.configure_cli_logger(__file__)

    raw_txn_hash = common_utils.get_argument_value("--txn-hash") or ""
    cl_account_private_key = common_utils.get_argument_value("--waves-private-key")
    chain_id_str = common_utils.get_argument_value("--chain-id") or "S"

    if not (cl_account_private_key and len(raw_txn_hash) > 0):
        print(
            """Prepares the chain_contract.withdraw transaction from an Execution Layer (Ethereum) transaction hash.
Required arguments:
  transfer-e2c-withdraw.py --txn-hash <Ethereum transaction hash in HEX> --waves-private-key <Waves private key in base58> 
Additional optional arguments:
  --chain-id <S|T|W> (default: S): S - StageNet, T - TestNet. Not supported for now: W - MainNet""",
            file=sys.stderr,
        )
        exit(1)

    txn_hash = HexBytes(bytes.fromhex(common_utils.clean_hex_prefix(raw_txn_hash)))

    network = networks.select(chain_id_str)

    cl_account = pw.Address(privateKey=cl_account_private_key)

    txn_data: TxData = network.w3.eth.get_transaction(txn_hash)
    assert "blockHash" in txn_data and "value" in txn_data

    log.info(f"[E] Bridge.sendNative transaction data: {Web3.to_json(txn_data)}")  # type: ignore

    transfer_params = network.el_bridge.get_transfer_params(
        txn_data["blockHash"], txn_hash
    )
    log.info(f"[C] Transfer params: {transfer_params}")

    withdraw = network.cl_chain_contract.prepareWithdraw(
        cl_account,
        transfer_params.block_with_transfer_hash.hex(),
        transfer_params.merkle_proofs,
        transfer_params.transfer_index_in_block,
        txn_data["value"],
    )
    print(json.dumps(withdraw))
    log.info("Done")


if __name__ == "__main__":
    main()
