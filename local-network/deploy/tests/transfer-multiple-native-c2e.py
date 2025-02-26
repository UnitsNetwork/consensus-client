#!/usr/bin/env python
from decimal import Decimal
from time import sleep

from eth_typing import ChecksumAddress
from units_network import units, waves
from units_network.common_utils import configure_cli_logger
from web3.types import Wei

from local.common import C2ETransfer
from local.network import get_local


def main():
    log = configure_cli_logger(__file__)
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
    cl_token = network.cl_chain_contract.getNativeToken()
    log.info(f"[C] Token id: {cl_token.assetId}")

    expected_balances: dict[ChecksumAddress, Wei] = {}
    txns = []
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
        txn = network.cl_chain_contract.transfer(
            t.from_account, t.to_account.address, cl_token, t.waves_atomic_amount
        )
        txns.append(txn)

    for i, txn in enumerate(txns):
        waves.force_success(
            log, txn, "Can not send the ChainContract.transfer transaction"
        )
        log.info(f"[C] #{i} ChainContract.transfer result: {txn}")

    wait_blocks = 2
    el_curr_height = network.w3.eth.block_number
    el_target_height = el_curr_height + wait_blocks
    while el_curr_height < el_target_height:
        log.info(f"[E] Waiting {el_target_height}, current height: {el_curr_height}")
        sleep(2)
        el_curr_height = network.w3.eth.block_number

    for to_address, expected_balance in expected_balances.items():
        balance_after = network.w3.eth.get_balance(to_address)
        log.info(
            f"[E] {to_address} balance after: {units.wei_to_raw(balance_after)} UNIT0, expected: {units.wei_to_raw(expected_balance)} UNIT0"
        )

        assert balance_after == expected_balance
    log.info("Done")
    pass


if __name__ == "__main__":
    main()
