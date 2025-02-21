#!/usr/bin/env python
from decimal import Decimal
from time import sleep

from eth_account.signers.local import LocalAccount
from eth_typing import ChecksumAddress
from pywaves import Address
from units_network import common_utils, units, waves
from units_network.common_utils import configure_cli_logger
from units_network.merkle import get_merkle_proofs
from web3.types import FilterParams, Wei

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

    log.info(
        f"[C] Prepare: Approve EL spendings by StandardBridge {network.el_standard_bridge.contract_address}"
    )

    min_el_balance: dict[LocalAccount, Wei] = {}
    for t in transfers:
        min_el_balance[t.to_account] = Wei(
            min_el_balance.get(t.to_account, 0) + t.wei_amount
        )

    for el_account, min_balance in min_el_balance.items():
        log.info(f"[E] Approve transfer of {min_balance} for StandardBridge")
        approve_txn = network.el_test_erc20.approve(
            network.el_standard_bridge.contract_address,
            min_balance,
            el_account,
        )
        approve_result = network.w3.eth.wait_for_transaction_receipt(approve_txn)
        log.info(f"Approved: {approve_result}")

    log.info("[E] Start E2C transfers")

    log.info("[E] Initiate transfers: Send StandardBridge.bridgeERC20")
    bridge_txns = []
    for t in transfers:
        log.info(
            f"[E] Send StandardBridge.bridgeERC20: {t.wei_amount} from {t.el_account.address}"
        )
        bridge_txn = network.el_standard_bridge.bridge_erc20(
            network.el_test_erc20.contract_address,
            common_utils.waves_public_key_hash_bytes(t.cl_account.address),
            t.wei_amount,
            t.el_account,
        )
        bridge_txns.append(bridge_txn)

    block_hash = None
    block_number = 0
    for txn in bridge_txns:
        bridge_result = network.w3.eth.wait_for_transaction_receipt(txn)
        log.info(f"Sent: {bridge_result}")
        curr_block_hash = bridge_result["blockHash"]
        if block_hash:
            if curr_block_hash != block_hash:
                raise Exception(
                    f"Expected all transactions processed in one block {block_hash}, found {curr_block_hash}"
                )
        else:
            block_hash = curr_block_hash
            block_number = bridge_result["blockNumber"]

    if not block_hash:
        raise Exception("Impossible: block_hash is empty")

    log.info(f"[E] Get logs of {block_hash.hex()}")
    block_logs = network.w3.eth.get_logs(
        # TODO: filter params changed
        FilterParams(
            blockHash=block_hash,
            address=network.el_standard_bridge.contract_address,
        )
    )
    filtered_logs = block_logs  # TODO:
    log.info(f"Block logs: {filtered_logs}")

    merkle_leaves = []
    for i, x in enumerate(block_logs):
        evt = network.el_standard_bridge.parse_erc20_bridge_initiated(x)
        log.info(f"[E] Parsed event: {evt}")
        merkle_leaves.append(evt.to_merkle_leaf().hex())

    # E2CTransferParams(
    #     block_with_transfer_hash=block_hash,
    #     merkle_proofs=get_merkle_proofs(merkle_leaves, transfer_index_in_block),
    #     transfer_index_in_block=transfer_index_in_block,
    # )

    log.info("[C] Wait the block on ChainContract")
    withdraw_block_meta = network.require_settled_block(block_hash, block_number)

    log.info("[C] Wait the block finalization")
    network.require_finalized_block(withdraw_block_meta)

    log.info("[C] Call ChainContract.withdrawAsset")
    expected_cl_balances: dict[Address, int] = {}
    for t in transfers:
        if t.cl_account not in expected_cl_balances:
            balance_before = t.cl_account.balance(network.cl_test_asset.assetId)

            expected_cl_balances[t.cl_account] = balance_before
            log.info(
                f"[C] {t.cl_account.address} balance before: {balance_before} (atomic: {balance_before})"
            )
        expected_cl_balances[t.cl_account] = (
            expected_cl_balances[t.cl_account] + t.waves_atomic_amount
        )

    withdraw_txns = []
    for i, t in enumerate(transfers):
        txn = network.cl_chain_contract.withdrawAsset(
            sender=transfers[i].cl_account,
            blockHashWithTransfer=block_hash.hex(),
            merkleProofs=get_merkle_proofs(merkle_leaves, i),
            transferIndexInBlock=i,
            atomicAmount=transfers[i].waves_atomic_amount,
            asset=network.cl_test_asset,
        )
        withdraw_txns.append(txn)

    for txn in withdraw_txns:
        waves.force_success(log, txn, "Can not withdraw assets")

    for acc, expected_balance in expected_cl_balances.items():
        actual_balance = acc.balance(network.cl_test_asset.assetId)
        log.info(
            f"[C] {acc.address} balance after: {actual_balance} (atomic: {actual_balance}), "
            + f"expected: {expected_balance} (atomic: {expected_balance})"
        )
        assert actual_balance == expected_balance

    exit(0)

    cl_asset = network.cl_test_asset
    log.info(
        f"[C] Test asset id: {cl_asset.assetId}, ERC20 address: {network.el_test_erc20.contract_address}"
    )

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
