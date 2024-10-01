#!/usr/bin/env python
import os
from decimal import Decimal

from eth_typing import ChecksumAddress
from units_network import units, waves
from web3.types import Wei

from local.common import C2ETransfer, configure_script_logger
from local.network import get_local


def main():
    log = configure_script_logger(os.path.basename(__file__))
    network = get_local()

    transfers = [
        C2ETransfer(
            cl_account=network.cl_rich_accounts[0],
            el_account=network.el_rich_accounts[0],
            raw_amount=Decimal("0.01"),
        ),
        C2ETransfer(
            cl_account=network.cl_rich_accounts[0],
            el_account=network.el_rich_accounts[1],
            raw_amount=Decimal("0.02"),
        ),
        # Same as first
        C2ETransfer(
            cl_account=network.cl_rich_accounts[0],
            el_account=network.el_rich_accounts[0],
            raw_amount=Decimal("0.01"),
        ),
        C2ETransfer(
            cl_account=network.cl_rich_accounts[1],
            el_account=network.el_rich_accounts[1],
            raw_amount=Decimal("0.03"),
        ),
    ]

    el_curr_height = network.w3.eth.block_number
    cl_token = network.cl_chain_contract.getToken()
    log.info(f"[C] Token id: {cl_token.assetId}")

    expected_balances: dict[ChecksumAddress, Wei] = {}
    for i, t in enumerate(transfers):
        to_address = t.to_account.address
        if to_address in expected_balances:
            expected_balances[to_address] = Wei(
                expected_balances[to_address] + t.wei_amount
            )
        else:
            balance_before = network.w3.eth.get_balance(to_address)
            expected_balances[to_address] = Wei(balance_before + t.wei_amount)
            log.info(
                f"[E] {to_address} balance before: {units.wei_to_raw(balance_before)} UNIT0"
            )

        log.info(f"[C] #{i} Call ChainContract.transfer for {t}")
        transfer_result = network.cl_chain_contract.transfer(
            t.from_account, t.to_account.address, cl_token, t.waves_atomic_amount
        )
        waves.force_success(
            log, transfer_result, "Can not send the chain_contract.transfer transaction"
        )
        log.info(f"[C] #{i} ChainContract.transfer result: {transfer_result}")

    network.el_bridge.waitForWithdrawals(
        el_curr_height, [(t.to_account, t.wei_amount) for t in transfers]
    )

    for to_address, expected_balance in expected_balances.items():
        balance_after = network.w3.eth.get_balance(to_address)
        log.info(
            f"[E] {to_address} balance after: {units.wei_to_raw(balance_after)} UNIT0, expected: {units.wei_to_raw(expected_balance)} UNIT0"
        )

        assert balance_after == expected_balance

    log.info("Done")


if __name__ == "__main__":
    main()
