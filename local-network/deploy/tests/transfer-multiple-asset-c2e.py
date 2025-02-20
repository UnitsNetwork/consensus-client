#!/usr/bin/env python
from decimal import Decimal
from time import sleep

from eth_typing import ChecksumAddress
from pywaves import Address
from units_network import common_utils, units, waves
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

    log.info("[E] Deposit initial balance into StandardBridge")
    total_amount = Wei(0)
    for t in transfers:
        total_amount = Wei(total_amount + t.wei_amount)

    log.info("[E] Deposit: Approve transfers")
    approve_txn = network.el_test_erc20.approve(
        network.el_standard_bridge.contract_address,
        total_amount,
        network.el_rich_accounts[0],
    )
    approve_result = network.w3.eth.wait_for_transaction_receipt(approve_txn)
    log.info(f"[E] Approved: {approve_result}")

    log.info("[E] Deposit: Send StandardBridge.bridgeERC20")
    bridge_txn = network.el_standard_bridge.bridge_erc20(
        network.el_test_erc20.contract_address,
        common_utils.waves_public_key_hash_bytes(network.cl_rich_accounts[0].address),
        total_amount,
        network.el_rich_accounts[0],
    )
    bridge_result = network.w3.eth.wait_for_transaction_receipt(bridge_txn)
    log.info(f"[E] Sent: {bridge_result}")

    cl_asset = network.cl_test_asset
    log.info(
        f"[C] Test asset id: {cl_asset.assetId}, ERC20 address: {network.el_test_erc20.contract_address}"
    )

    min_cl_balance: dict[Address, int] = {}
    expected_balances: dict[ChecksumAddress, Wei] = {}
    for t in transfers:
        to_address = t.to_account.address
        if to_address not in expected_balances:
            balance_before = network.el_test_erc20.get_balance(to_address)
            expected_balances[to_address] = balance_before
            log.info(
                f"[E] {to_address} balance before: {units.wei_to_raw(balance_before)} (atomic: {balance_before})"
            )

        expected_balances[to_address] = Wei(
            expected_balances[to_address] + t.wei_amount
        )

        min_cl_balance[t.from_account] = (
            min_cl_balance.get(t.from_account, 0) + t.waves_atomic_amount
        )

    log.info("[C] Distrubute tokens before sending")
    for cl_address, min_balance in min_cl_balance.items():
        if cl_address == network.cl_test_asset_issuer:
            continue
        transfer_txn = network.cl_test_asset_issuer.sendAsset(
            cl_address, network.cl_test_asset, min_balance
        )
        waves.force_success(
            log, transfer_txn, f"Can not send {min_balance} test assets to {cl_address}"
        )

    for i, t in enumerate(transfers):
        log.info(f"[C] #{i} Call ChainContract.transfer for {t}")
        transfer_result = network.cl_chain_contract.transfer(
            t.from_account, t.to_account.address, cl_asset, t.waves_atomic_amount
        )
        waves.force_success(
            log, transfer_result, "Can not send the chain_contract.transfer transaction"
        )
        log.info(f"[C] #{i} ChainContract.transfer result: {transfer_result}")

    el_curr_height = network.w3.eth.block_number

    wait_blocks = 2
    el_target_height = el_curr_height + wait_blocks
    while el_curr_height < el_target_height:
        log.info(f"[E] Waiting {el_target_height}, current height: {el_curr_height}")
        sleep(2)
        el_curr_height = network.w3.eth.block_number

    for to_address, expected_balance in expected_balances.items():
        balance_after = network.el_test_erc20.get_balance(to_address)
        log.info(
            f"[E] {to_address} balance after: {units.wei_to_raw(balance_after)} (atomic: {balance_after}), "
            + f"expected: {units.wei_to_raw(expected_balance)} (atomic: {expected_balance})"
        )
        assert balance_after == expected_balance

    log.info("Done")


if __name__ == "__main__":
    main()
