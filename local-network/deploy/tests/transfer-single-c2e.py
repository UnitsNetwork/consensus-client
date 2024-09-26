#!/usr/bin/env python
# One C2E transfer
import os

from local import waves_txs
from local.common import C2ETransfer, configure_script_logger
from local.el import el_wait_for_withdraw
from local.network import get_local
from web3.types import Wei


def main():
    log = configure_script_logger(os.path.basename(__file__))
    (network, accounts) = get_local()

    transfer = C2ETransfer(
        cl_account=accounts.waves_miners[0].account,
        el_account=network.w3.eth.account.from_key(
            "0x6f077b245cb0e1ec59796366ebd3a254e604cf5686b64b7878ae730eb9ad9570"
        ),
        raw_amount=0.01,
    )
    log.info(f"Sending {transfer}")

    balance_before = network.w3.eth.get_balance(transfer.to_account.address)
    log.info(f"[C] Balance before: {balance_before / 10**18} UNIT0")

    token = network.cl_chain_contract.getToken()
    log.info(f"[C] Token id: {token.assetId}")

    el_curr_height = network.w3.eth.block_number
    transfer_result = network.cl_chain_contract.transfer(
        transfer.from_account, transfer.to_account, token, transfer.waves_atomic_amount
    )
    waves_txs.force_success(
        log, transfer_result, "Can not send the chain_contract.transfer transaction"
    )
    log.info(f"[C] Transfer result: {transfer_result}")

    el_wait_for_withdraw(
        log,
        network.w3,
        el_curr_height,
        [transfer],
    )
    balance_after = network.w3.eth.get_balance(transfer.to_account.address)
    log.info(f"Balance after: {balance_after / 10**18} UNIT0")

    assert balance_after == Wei(balance_before + transfer.wei_amount)
    log.info("Done")


if __name__ == "__main__":
    main()
