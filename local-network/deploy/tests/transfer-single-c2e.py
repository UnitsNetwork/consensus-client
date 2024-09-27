#!/usr/bin/env python
import os

from units_network import waves
from web3.types import Wei

from local.common import C2ETransfer, configure_script_logger
from local.network import get_local


def main():
    log = configure_script_logger(os.path.basename(__file__))
    network = get_local()

    transfer = C2ETransfer(
        cl_account=network.cl_rich_accounts[0],
        el_account=network.el_rich_accounts[0],
        raw_amount=0.01,
    )
    log.info(f"Sending {transfer}")

    balance_before = network.w3.eth.get_balance(transfer.to_account.address)
    log.info(f"[C] Balance before: {balance_before / 10**18} UNIT0")

    token = network.cl_chain_contract.getToken()
    log.info(f"[C] Token id: {token.assetId}")

    el_curr_height = network.w3.eth.block_number
    transfer_result = network.cl_chain_contract.transfer(
        transfer.from_account,
        transfer.to_account.address,
        token,
        transfer.waves_atomic_amount,
    )
    waves.force_success(
        log, transfer_result, "Can not send the chain_contract.transfer transaction"
    )
    log.info(f"[C] ChainContract.transfer result: {transfer_result}")

    network.el_bridge.waitForWithdrawals(
        el_curr_height,
        [(transfer.to_account, transfer.wei_amount)],
    )
    balance_after = network.w3.eth.get_balance(transfer.to_account.address)
    log.info(f"Balance after: {balance_after / 10**18} UNIT0")

    assert balance_after == Wei(balance_before + transfer.wei_amount)
    log.info("Done")


if __name__ == "__main__":
    main()
