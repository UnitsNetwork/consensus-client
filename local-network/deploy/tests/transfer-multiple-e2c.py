#!/usr/bin/env python
# Multiple E2C transfers
import os
from typing import List, Tuple

from hexbytes import HexBytes
from local.accounts import accounts
from local.common import E2CTransfer
from local.network import get_network
from units_network import common_utils
from web3 import Web3
from web3.types import Nonce, TxReceipt


def main():
    log = common_utils.configure_script_logger(os.path.basename(__file__))
    network = get_network()
    from local import waves_txs

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

    txn_hashes: List[Tuple[E2CTransfer, HexBytes]] = []
    nonces = {
        x.address: network.w3.eth.get_transaction_count(x.address)
        for x in accounts.el_rich_accounts
    }
    for i, t in enumerate(transfers):
        log.info(f"[E] #{i} Call Bridge.sendNative for {t}")
        nonce = nonces[t.el_account.address]
        txn_hash = network.el_bridge.sendNative(
            from_eth_account=t.el_account,
            to_waves_pk_hash=common_utils.waves_public_key_hash_bytes(
                t.cl_account.address
            ),
            amount=t.wei_amount,
            nonce=nonce,
        )
        nonces[t.el_account.address] = Nonce(nonce + 1)
        txn_hashes.append((t, txn_hash))

    cl_token_id = network.cl_chain_contract.getToken()
    for i, (t, txn_hash) in enumerate(txn_hashes):
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

        balance_before = t.cl_account.balance(cl_token_id.assetId)
        log.info(f"[C] #{i} Balance before of {t.cl_account.address}: {balance_before}")

        withdraw_result = network.cl_chain_contract.withdraw(
            t.cl_account,
            transfer_params.block_with_transfer_hash.hex(),
            transfer_params.merkle_proofs,
            transfer_params.transfer_index_in_block,
            t.wei_amount,
        )
        waves_txs.force_success(
            log, withdraw_result, "Can not send the chain_contract.withdraw transaction"
        )
        log.info(f"[C] #{i} Withdraw result: {withdraw_result}")

        balance_after = t.cl_account.balance(cl_token_id.assetId)
        log.info(f"[C] #{i} Balance after: {balance_after}")

        assert balance_after == (balance_before + t.waves_atomic_amount)
    log.info("Done")


if __name__ == "__main__":
    main()
