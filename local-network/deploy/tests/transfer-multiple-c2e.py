#!/usr/bin/env python
# Multiple C2E transfers
import os

from eth_typing import ChecksumAddress
from local.accounts import accounts
from local.common import C2ETransfer
from local.el import el_wait_for_withdraw
from local.network import get_network
from units_network import common_utils
from web3.types import Wei


def main():
    log = common_utils.configure_script_logger(os.path.basename(__file__))
    network = get_network()

    from local import waves_txs

    transfers = [
        C2ETransfer(
            cl_account=accounts.cl_rich_accounts[0],
            el_account=accounts.el_rich_accounts[0],
            raw_amount=0.01,
        ),
        C2ETransfer(
            cl_account=accounts.cl_rich_accounts[0],
            el_account=accounts.el_rich_accounts[0],
            raw_amount=0.02,
        ),
        # Same as first
        C2ETransfer(
            cl_account=accounts.cl_rich_accounts[1],
            el_account=accounts.el_rich_accounts[0],
            raw_amount=0.01,
        ),
        C2ETransfer(
            cl_account=accounts.cl_rich_accounts[1],
            el_account=accounts.el_rich_accounts[1],
            raw_amount=0.03,
        ),
    ]

    el_curr_height = network.w3.eth.block_number
    token = network.cl_chain_contract.getToken()
    log.info(f"[C] Token id: {token.assetId}")

    expected_balances: dict[ChecksumAddress, Wei] = {}
    for i, t in enumerate(transfers):
        el_address = t.el_account.address
        if el_address in expected_balances:
            expected_balances[el_address] = Wei(
                expected_balances[el_address] + t.wei_amount
            )
        else:
            balance_before = network.w3.eth.get_balance(el_address)
            expected_balances[el_address] = Wei(balance_before + t.wei_amount)
            log.info(
                f"[E] {el_address} balance before: {balance_before / 10**18} UNIT0"
            )

        log.info(f"[C] #{i} Call chain_contract.transfer for {t}")
        transfer_result = network.cl_chain_contract.transfer(
            t.cl_account, t.el_account, token, t.waves_atomic_amount
        )
        waves_txs.force_success(
            log, transfer_result, "Can not send the chain_contract.transfer transaction"
        )
        log.info(f"[C] #{i} Transfer result: {transfer_result}")

    el_wait_for_withdraw(log, network.w3, el_curr_height, transfers)

    for el_address, expected_balance in expected_balances.items():
        balance_after = network.w3.eth.get_balance(el_address)
        log.info(f"[E] {el_address} balance after: {balance_after / 10**18} UNIT0")

        assert balance_after == expected_balance

    log.info("Done")


if __name__ == "__main__":
    main()
