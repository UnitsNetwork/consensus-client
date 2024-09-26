#!/usr/bin/env python
# One E2C transfer
import os

from local.accounts import accounts
from local.common import E2CTransfer, configure_script_logger
from local.network import get_network
from units_network import common_utils
from web3 import Web3
from web3.types import TxReceipt


def main():
    log = configure_script_logger(os.path.basename(__file__))
    network = get_network()
    from local import waves_txs

    transfer = E2CTransfer(
        el_account=network.w3.eth.account.from_key(
            "0xae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f"
        ),
        cl_account=accounts.waves_miners[0].account,
        raw_amount=0.01,
    )

    log.info(f"Sending {transfer}")

    log.info("[E] Call Bridge sendNative")
    send_native_result = network.el_bridge.sendNative(
        from_eth_account=transfer.from_account,
        to_waves_pk_hash=common_utils.waves_public_key_hash_bytes(
            transfer.to_account.address
        ),
        amount=transfer.wei_amount,
    )

    send_native_receipt: TxReceipt = network.w3.eth.wait_for_transaction_receipt(
        send_native_result
    )
    log.info(f"[E] sendNative receipt: {Web3.to_json(send_native_receipt)}")  # type: ignore

    transfer_params = network.el_bridge.getTransferParams(
        send_native_receipt["blockHash"], send_native_receipt["transactionHash"]
    )
    log.info(f"[C] Transfer params: {transfer_params}")

    # Wait for a block confirmation on Consensus layer
    withdraw_block_meta = network.cl_chain_contract.waitForBlock(
        transfer_params.block_with_transfer_hash.hex()
    )
    log.info(f"[C] Withdraw block meta: {withdraw_block_meta}, wait for finalization")
    network.cl_chain_contract.waitForFinalized(withdraw_block_meta)

    cl_token_id = network.cl_chain_contract.getToken()
    balance_before = transfer.to_account.balance(cl_token_id.assetId)
    log.info(f"[C] Balance before: {balance_before}")

    withdraw_result = network.cl_chain_contract.withdraw(
        transfer.to_account,
        transfer_params.block_with_transfer_hash.hex(),
        transfer_params.merkle_proofs,
        transfer_params.transfer_index_in_block,
        transfer.wei_amount,
    )
    waves_txs.force_success(
        log, withdraw_result, "Can not send the chain_contract.withdraw transaction"
    )
    log.info(f"[C] Withdraw result: {withdraw_result}")

    balance_after = transfer.to_account.balance(cl_token_id.assetId)
    log.info(f"[C] Balance after: {balance_after}")

    assert balance_after == (balance_before + transfer.waves_atomic_amount)
    log.info("Done")


if __name__ == "__main__":
    main()
