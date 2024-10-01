#!/usr/bin/env python
import os
from decimal import Decimal
from typing import List, Tuple

from hexbytes import HexBytes
from pywaves import pw
from units_network import common_utils, exceptions, units, waves
from units_network.bridge import E2CTransferParams
from web3 import Web3
from web3.types import Nonce, TxReceipt

from local.common import E2CTransfer, configure_script_logger
from local.network import get_local


def main():
    log = configure_script_logger(os.path.basename(__file__))
    network = get_local()

    transfers = [
        E2CTransfer(
            el_account=network.el_rich_accounts[0],
            cl_account=network.cl_rich_accounts[0],
            raw_amount=Decimal("0.01"),
        ),
        E2CTransfer(
            el_account=network.el_rich_accounts[1],
            cl_account=network.cl_rich_accounts[0],
            raw_amount=Decimal("0.02"),
        ),
        # Same as first
        E2CTransfer(
            el_account=network.el_rich_accounts[0],
            cl_account=network.cl_rich_accounts[0],
            raw_amount=Decimal("0.01"),
        ),
        E2CTransfer(
            el_account=network.el_rich_accounts[1],
            cl_account=network.cl_rich_accounts[1],
            raw_amount=Decimal("0.03"),
        ),
    ]

    send_native_txn_hashes: List[Tuple[E2CTransfer, HexBytes]] = []
    nonces = {
        x.address: network.w3.eth.get_transaction_count(x.address)
        for x in network.el_rich_accounts
    }
    for i, t in enumerate(transfers):
        log.info(f"[E] #{i} Call Bridge.sendNative for {t}")
        nonce = nonces[t.from_account.address]
        txn_hash = network.el_bridge.send_native(
            from_eth_account=t.from_account,
            to_waves_pk_hash=common_utils.waves_public_key_hash_bytes(
                t.to_account.address
            ),
            amount=t.wei_amount,
            nonce=nonce,
        )
        nonces[t.from_account.address] = Nonce(nonce + 1)
        send_native_txn_hashes.append((t, txn_hash))

    cl_token = network.cl_chain_contract.getToken()

    expected_balances: dict[pw.Address, int] = {}
    for i, (t, txn_hash) in enumerate(send_native_txn_hashes):
        to_account = t.to_account
        if to_account in expected_balances:
            expected_balances[to_account] += t.waves_atomic_amount
        else:
            balance_before = to_account.balance(cl_token.assetId)
            expected_balances[to_account] = balance_before + t.waves_atomic_amount
            log.info(
                f"[C] {to_account.address} balance before: {units.waves_atomic_to_raw(balance_before)}"
            )

    withdraw_txn_params: List[Tuple[E2CTransferParams, E2CTransfer]] = []
    while not withdraw_txn_params:
        for i, (t, txn_hash) in enumerate(send_native_txn_hashes):
            txn_receipt: TxReceipt = network.w3.eth.wait_for_transaction_receipt(
                txn_hash
            )
            log.info(f"[E] #{i} Bridge.sendNative receipt: {Web3.to_json(txn_receipt)}")  # type: ignore

            transfer_params = network.el_bridge.get_transfer_params(
                txn_receipt["blockHash"], txn_receipt["transactionHash"]
            )
            log.info(f"[C] #{i} Transfer params: {transfer_params}")
            withdraw_txn_params.append((transfer_params, t))

            try:
                withdraw_block_meta = network.require_settled_block(
                    transfer_params.block_with_transfer_hash
                )
                log.info(f"[C] Found block on chain contract: {withdraw_block_meta}")

                network.require_finalized_block(withdraw_block_meta)
            except exceptions.BlockDisappeared as e:
                log.warning(f"{e}. Rollback? Retry again")
                withdraw_txn_params = []
                break

    withdraw_txn_ids: List[E2CTransfer] = []
    for transfer_params, t in withdraw_txn_params:
        withdraw_result = network.cl_chain_contract.withdraw(
            t.to_account,
            transfer_params.block_with_transfer_hash.hex(),
            transfer_params.merkle_proofs,
            transfer_params.transfer_index_in_block,
            t.wei_amount,
        )
        waves.force_success(
            log,
            withdraw_result,
            "Can not send the ChainContract.Withdraw transaction",
            wait=False,
        )
        withdraw_txn_ids.append(withdraw_result["id"])  # type: ignore

    for i, txn_id in enumerate(withdraw_txn_ids):
        withdraw_result = waves.wait_for(txn_id)
        log.info(f"[C] #{i} ChainContract.withdraw result: {withdraw_result}")

    for to_account, expected_balance in expected_balances.items():
        balance_after = to_account.balance(cl_token.assetId)

        log.info(
            f"[C] {to_account.address} balance after: {units.waves_atomic_to_raw(balance_after)}, expected: {units.waves_atomic_to_raw(expected_balance)} UNIT0"
        )

        assert balance_after == expected_balance

    log.info("Done")


if __name__ == "__main__":
    main()
