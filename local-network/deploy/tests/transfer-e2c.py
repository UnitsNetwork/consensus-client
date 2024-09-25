#!/usr/bin/env python
# One E2C transfer
import os

from units_network import common_utils
from web3 import Web3
from web3.types import TxReceipt

from local.accounts import accounts
from local.network import get_network


def main():
    log = common_utils.configure_script_logger(os.path.basename(__file__))

    network = get_network()

    from local import waves_txs

    cl_account = accounts.waves_miners[0].account
    el_account = network.w3.eth.account.from_key(
        "0xae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f"
    )

    raw_amount = "0.01"
    amount = Web3.to_wei(raw_amount, "ether")
    log.info(
        f"Sending {raw_amount} Unit0 ({amount} Wei) from {el_account.address} (E) to {cl_account.address} (C) using Bridge on {network.el_bridge.address} (E)"
    )

    log.info("[E] Call Bridge sendNative")
    send_native_result = network.el_bridge.sendNative(
        from_eth_account=el_account,
        to_waves_pk_hash=common_utils.waves_public_key_hash_bytes(cl_account.address),
        amount=amount,
    )

    send_native_receipt: TxReceipt = network.w3.eth.wait_for_transaction_receipt(
        send_native_result
    )
    log.info(f"[E] sendNative receipt: {Web3.to_json(send_native_receipt)}")  # type: ignore

    proofs = network.el_bridge.getTransferProofs(
        send_native_receipt["blockHash"], send_native_receipt["transactionHash"]
    )
    log.info(f"[C] Transfer params: {Web3.to_json(proofs)}")  # type: ignore

    # Wait for a block confirmation on Consensus layer
    withdraw_block_meta = network.cl_chain_contract.waitForBlock(
        proofs["block_with_transfer_hash"].hex()
    )
    log.info(f"[C] Withdraw block meta: {withdraw_block_meta}, wait for finalization")
    network.cl_chain_contract.waitForFinalized(withdraw_block_meta)

    cl_token_id = network.cl_chain_contract.getToken()
    balance_before = cl_account.balance(cl_token_id.assetId)
    log.info(f"[C] Balance before: {balance_before}")

    withdraw_result = network.cl_chain_contract.withdraw(
        cl_account,
        proofs["block_with_transfer_hash"].hex(),
        proofs["merkle_proofs"],
        proofs["transfer_index_in_block"],
        amount,
    )
    waves_txs.force_success(
        log, withdraw_result, "Can not send the chain_contract.withdraw transaction"
    )
    log.info(f"[C] Withdraw result: {withdraw_result}")

    balance_after = cl_account.balance(cl_token_id.assetId)
    log.info(f"[C] Balance after: {balance_after}")

    assert balance_after == (balance_before + int(float(raw_amount) * 10**8))
    log.info("Done")


if __name__ == "__main__":
    main()
