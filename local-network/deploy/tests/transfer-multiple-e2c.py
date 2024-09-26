#!/usr/bin/env python
# Multiple E2C transfers
import os
from typing import List, Tuple

from hexbytes import HexBytes
from local import waves_txs
from local.common import E2CTransfer, configure_script_logger
from local.network import get_local
from pywaves import pw
from units_network import common_utils
from web3 import Web3
from web3.types import Nonce, TxReceipt


def main():
    log = configure_script_logger(os.path.basename(__file__))
    (network, accounts) = get_local()

    transfers = [
        E2CTransfer(
            el_account=accounts.el_rich_accounts[0],
            cl_account=accounts.cl_rich_accounts[0],
            raw_amount=0.01,
        ),
        E2CTransfer(
            el_account=accounts.el_rich_accounts[0],
            cl_account=accounts.cl_rich_accounts[0],
            raw_amount=0.02,
        ),
        # Same as first
        E2CTransfer(
            el_account=accounts.el_rich_accounts[0],
            cl_account=accounts.cl_rich_accounts[1],
            raw_amount=0.01,
        ),
        E2CTransfer(
            el_account=accounts.el_rich_accounts[1],
            cl_account=accounts.cl_rich_accounts[1],
            raw_amount=0.03,
        ),
    ]

    send_native_txn_hashes: List[Tuple[E2CTransfer, HexBytes]] = []
    nonces = {
        x.address: network.w3.eth.get_transaction_count(x.address)
        for x in accounts.el_rich_accounts
    }
    for i, t in enumerate(transfers):
        log.info(f"[E] #{i} Call Bridge.sendNative for {t}")
        nonce = nonces[t.from_account.address]
        txn_hash = network.el_bridge.sendNative(
            from_eth_account=t.from_account,
            to_waves_pk_hash=common_utils.waves_public_key_hash_bytes(
                t.to_account.address
            ),
            amount=t.wei_amount,
            nonce=nonce,
        )
        nonces[t.from_account.address] = Nonce(nonce + 1)
        send_native_txn_hashes.append((t, txn_hash))

    cl_token_id = network.cl_chain_contract.getToken()

    expected_balances: dict[pw.Address, int] = {}
    withdraw_txn_ids: List[Tuple[E2CTransfer, str]] = []
    for i, (t, txn_hash) in enumerate(send_native_txn_hashes):
        to_account = t.to_account
        if to_account in expected_balances:
            expected_balances[to_account] += t.waves_atomic_amount
        else:
            balance_before = to_account.balance(cl_token_id.assetId)
            expected_balances[to_account] = balance_before + t.waves_atomic_amount
            log.info(f"[C] {to_account.address} balance before: {balance_before}")

        receipt: TxReceipt = network.w3.eth.wait_for_transaction_receipt(txn_hash)
        log.info(f"[E] #{i} Bridge.sendNative receipt: {Web3.to_json(receipt)}")  # type: ignore

        transfer_params = network.el_bridge.getTransferParams(
            receipt["blockHash"], receipt["transactionHash"]
        )
        log.info(f"[C] #{i} Transfer params: {transfer_params}")

        # Wait for a block confirmation on Consensus layer
        withdraw_block_meta = network.cl_chain_contract.waitForBlock(
            transfer_params.block_with_transfer_hash.hex()
        )
        log.info(
            f"[C] #{i} Withdraw block meta: {withdraw_block_meta}, wait for finalization"
        )
        network.cl_chain_contract.waitForFinalized(withdraw_block_meta)

        withdraw_result = network.cl_chain_contract.withdraw(
            t.to_account,
            transfer_params.block_with_transfer_hash.hex(),
            transfer_params.merkle_proofs,
            transfer_params.transfer_index_in_block,
            t.wei_amount,
        )
        waves_txs.force_success(
            log,
            withdraw_result,
            "Can not send the chain_contract.withdraw transaction",
            wait=False,
        )
        withdraw_txn_ids.append((t, withdraw_result["id"]))  # type: ignore

    for i, (t, txn_id) in enumerate(withdraw_txn_ids):
        withdraw_result = waves_txs.wait_for_txn(txn_id)
        log.info(f"[C] #{i} Withdraw result: {withdraw_result}")

    for to_account, expected_balance in expected_balances.items():
        balance_after = to_account.balance(cl_token_id.assetId)
        log.info(f"[C] {to_account.address} balance after: {balance_after}")

        assert balance_after == expected_balance

    log.info("Done")


if __name__ == "__main__":
    main()