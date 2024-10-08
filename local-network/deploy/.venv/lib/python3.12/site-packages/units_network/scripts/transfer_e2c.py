#!/usr/bin/env python
import sys
from decimal import Decimal

import pywaves as pw
from web3 import Web3
from web3.types import TxReceipt

from units_network import common_utils, exceptions, networks, units


def main():
    log = common_utils.configure_cli_logger(__file__)

    cl_account_private_key = common_utils.get_argument_value("--waves-private-key")
    el_account_private_key = common_utils.get_argument_value("--eth-private-key")
    chain_id_str = common_utils.get_argument_value("--chain-id") or "S"
    user_amount = Decimal(common_utils.get_argument_value("--amount") or "0.01")

    if not (
        cl_account_private_key
        and el_account_private_key
        and el_account_private_key.startswith("0x")
        and user_amount > 0
    ):
        print(
            """Transfer native tokens from Execution Layer (Ethereum) to Consensus Layer (Waves).
Required arguments:
  transfer-e2c.py --eth-private-key <Ethereum private key in HEX with 0x> --waves-private-key <Waves private key in base58> 
Additional optional arguments:
  --chain-id <S|T|W> (default: S): S - StageNet, T - TestNet. Not supported for now: W - MainNet
  --amount N (default: 0.01): amount of transferred Unit0 tokens""",
            file=sys.stderr,
        )
        exit(1)

    network = networks.select(chain_id_str)

    cl_account = pw.Address(privateKey=cl_account_private_key)
    el_account = network.w3.eth.account.from_key(el_account_private_key)

    wei_amount = units.raw_to_wei(user_amount)
    log.info(
        f"Sending {user_amount} Unit0 ({wei_amount} Wei) from {el_account.address} (E) to {cl_account.address} (C) using Bridge on {network.el_bridge.address} (E)"
    )

    log.info("[E] Call Bridge.sendNative")
    send_native_txn_hash = network.el_bridge.send_native(
        from_eth_account=el_account,
        to_waves_pk_hash=common_utils.waves_public_key_hash_bytes(cl_account.address),
        amount=wei_amount,
    )
    log.info(f"[E] Bridge.sendNative transaction hash: {send_native_txn_hash.hex()}")

    while True:
        send_native_receipt: TxReceipt = network.w3.eth.wait_for_transaction_receipt(
            send_native_txn_hash,
        )
        log.info(f"[E] Bridge.sendNative receipt: {Web3.to_json(send_native_receipt)}")  # type: ignore

        transfer_params = network.el_bridge.get_transfer_params(
            send_native_receipt["blockHash"], send_native_receipt["transactionHash"]
        )
        log.info(f"[C] Transfer params: {transfer_params}")

        try:
            withdraw_block_meta = network.require_settled_block(
                transfer_params.block_with_transfer_hash,
                send_native_receipt["blockNumber"],
            )
            log.info(f"[C] Found block on chain contract: {withdraw_block_meta}")

            network.require_finalized_block(withdraw_block_meta)
            break
        except exceptions.BlockDisappeared as e:
            log.warning(f"{e}. Rollback? Retry getting a receipt")
            continue

    withdraw_result = network.cl_chain_contract.withdraw(
        cl_account,
        transfer_params.block_with_transfer_hash.hex(),
        transfer_params.merkle_proofs,
        transfer_params.transfer_index_in_block,
        wei_amount,
    )
    log.info(f"[C] ChainContract.withdraw result: {withdraw_result}")
    log.info("Done")


if __name__ == "__main__":
    main()
