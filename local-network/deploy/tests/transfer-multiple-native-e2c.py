#!/usr/bin/env python
from decimal import Decimal
from typing import List, Tuple

from eth_typing import HexStr
from pywaves import pw
from units_network import common_utils, units, waves
from web3 import Web3
from web3.types import Nonce, TxReceipt

from local.common import Transfer, TransferBuilder
from local.network import get_local


def main():
    log = common_utils.configure_cli_logger(__file__)
    network = get_local()

    tb = TransferBuilder(network.cl_test_asset, network.el_test_erc20.decimals)
    transfers = [
        tb.create(
            el_account=network.el_rich_accounts[0],
            cl_account=network.cl_rich_accounts[0],
            user_amount=Decimal("0.01"),
        ),
        tb.create(
            el_account=network.el_rich_accounts[1],
            cl_account=network.cl_rich_accounts[0],
            user_amount=Decimal("0.02"),
        ),
        # Same as first
        tb.create(
            el_account=network.el_rich_accounts[0],
            cl_account=network.cl_rich_accounts[0],
            user_amount=Decimal("0.01"),
        ),
        tb.create(
            el_account=network.el_rich_accounts[1],
            cl_account=network.cl_rich_accounts[1],
            user_amount=Decimal("0.03"),
        ),
    ]

    send_native_txn_hashes: List[Tuple[Transfer, HexStr]] = []
    nonces = {
        x.address: network.w3.eth.get_transaction_count(x.address)
        for x in network.el_rich_accounts
    }
    for i, t in enumerate(transfers):
        log.info(f"[E] #{i} Call Bridge.sendNative for {t}")
        nonce = nonces[t.el_account.address]
        txn_hash = network.bridges.native_bridge.send_native(
            cl_to=t.cl_account,
            el_amount=t.el_atomic_amount,
            sender_account=t.el_account,
            nonce=nonce,
        )
        nonces[t.el_account.address] = Nonce(nonce + 1)
        send_native_txn_hashes.append((t, txn_hash))

    cl_token = network.cl_chain_contract.getNativeToken()

    expected_balances: dict[pw.Address, int] = {}
    for i, (t, txn_hash) in enumerate(send_native_txn_hashes):
        to_account = t.cl_account
        if to_account in expected_balances:
            expected_balances[to_account] += t.cl_atomic_amount
        else:
            balance_before = to_account.balance(cl_token.assetId)
            expected_balances[to_account] = balance_before + t.cl_atomic_amount
            log.info(
                f"[C] {to_account.address} balance before: {units.atomic_to_user(balance_before, cl_token.decimals)}"
            )

    withdraw_txn_params = []
    for i, (t, txn_hash) in enumerate(send_native_txn_hashes):
        txn_receipt: TxReceipt = network.w3.eth.wait_for_transaction_receipt(txn_hash)
        log.info(f"[E] #{i} Bridge.sendNative receipt: {Web3.to_json(txn_receipt)}")  # type: ignore

        transfer_params = network.bridges.get_e2c_transfer_params(
            txn_receipt["blockHash"], txn_receipt["transactionHash"]
        )
        log.info(f"[C] #{i} Transfer params: {transfer_params}")
        withdraw_txn_params.append((transfer_params, t))

        block_hash_str = txn_receipt["blockHash"].to_0x_hex()
        log.info(
            f"[C] Wait for a block {block_hash_str} on chain contract"
        )
        withdraw_block_meta = network.cl_chain_contract.waitForBlock(
            transfer_params.block_with_transfer_hash
        )
        log.info(f"[C] Found block on chain contract: {withdraw_block_meta}")

        log.info(
            f"[C] Wait for a finalized block {block_hash_str} on chain contract"
        )
        network.cl_chain_contract.waitForFinalized(withdraw_block_meta)

    withdraw_txn_ids: List[Transfer] = []
    for transfer_params, t in withdraw_txn_params:
        withdraw_result = network.cl_chain_contract.withdraw(
            t.cl_account,
            transfer_params.block_with_transfer_hash,
            transfer_params.merkle_proofs,
            transfer_params.transfer_index_in_block,
            t.el_atomic_amount,
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
            f"[C] {to_account.address} balance after: {units.atomic_to_user(balance_after, cl_token.decimals)}, "
            + f"expected: {units.atomic_to_user(expected_balance, cl_token.decimals)} UNIT0"
        )

        assert balance_after == expected_balance
    log.info("Done")
    pass


if __name__ == "__main__":
    main()
