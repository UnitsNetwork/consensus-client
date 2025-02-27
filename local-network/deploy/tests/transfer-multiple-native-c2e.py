#!/usr/bin/env python
from decimal import Decimal
from time import sleep

from eth_typing import ChecksumAddress
from units_network import units, waves
from units_network.common_utils import configure_cli_logger
from web3.types import Wei

from local.common import TransferBuilder
from local.network import get_local


def main():
    log = configure_cli_logger(__file__)
    network = get_local()

    tb = TransferBuilder(network.cl_test_asset, network.el_test_erc20.decimals)
    transfers = [
        tb.create(
            cl_account=network.cl_rich_accounts[0],
            el_account=network.el_rich_accounts[0],
            user_amount=Decimal("0.01"),
        ),
        tb.create(
            cl_account=network.cl_rich_accounts[0],
            el_account=network.el_rich_accounts[1],
            user_amount=Decimal("0.02"),
        ),
        # Same as first
        tb.create(
            cl_account=network.cl_rich_accounts[0],
            el_account=network.el_rich_accounts[0],
            user_amount=Decimal("0.01"),
        ),
        tb.create(
            cl_account=network.cl_rich_accounts[1],
            el_account=network.el_rich_accounts[1],
            user_amount=Decimal("0.03"),
        ),
    ]

    el_curr_height = network.w3.eth.block_number
    cl_token = network.cl_chain_contract.getNativeToken()
    log.info(f"[C] Token id: {cl_token.assetId}")

    expected_balances: dict[ChecksumAddress, Wei] = {}
    txns = []
    for i, t in enumerate(transfers):
        to_address = t.el_account.address
        if to_address in expected_balances:
            expected_balances[to_address] = Wei(
                expected_balances[to_address] + t.el_atomic_amount
            )
        else:
            balance_before = network.w3.eth.get_balance(to_address)
            expected_balances[to_address] = Wei(balance_before + t.el_atomic_amount)
            log.info(
                f"[E] {to_address} balance before: {units.atomic_to_user(balance_before, units.UNIT0_EL_DECIMALS)} UNIT0"
            )

        log.info(f"[C] #{i} Call ChainContract.transfer for {t}")
        txn = network.cl_chain_contract.transfer(
            t.cl_account, t.el_account.address, cl_token, t.cl_atomic_amount
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
            f"[E] {to_address} balance after: {units.atomic_to_user(balance_after, units.UNIT0_EL_DECIMALS)} UNIT0, "
            + f"expected: {units.atomic_to_user(expected_balance, units.UNIT0_EL_DECIMALS)} UNIT0"
        )

        assert balance_after == expected_balance
    log.info("Done")
    pass


if __name__ == "__main__":
    main()
