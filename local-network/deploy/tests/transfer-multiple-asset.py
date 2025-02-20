#!/usr/bin/env python
from decimal import Decimal
from time import sleep

from eth_account.signers.local import LocalAccount
from eth_typing import ChecksumAddress
from pywaves import Address
from units_network import common_utils, units, waves
from units_network.common_utils import configure_cli_logger
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

    log.info("[C] Prepare: Distrubute CL assets before sending")
    min_cl_balance: dict[Address, int] = {}
    min_el_balance: dict[LocalAccount, Wei] = {}
    for t in transfers:
        min_cl_balance[t.from_account] = (
            min_cl_balance.get(t.from_account, 0) + t.waves_atomic_amount
        )
        min_el_balance[t.to_account] = Wei(
            min_el_balance.get(t.to_account, 0) + t.wei_amount
        )

    for cl_address, min_balance in min_cl_balance.items():
        if cl_address == network.cl_test_asset_issuer:
            continue
        transfer_txn = network.cl_test_asset_issuer.sendAsset(
            cl_address, network.cl_test_asset, min_balance
        )
        waves.force_success(
            log, transfer_txn, f"Can not send {min_balance} test assets to {cl_address}"
        )

    log.info(
        f"[C] Prepare: Approve EL spendings by StandardBridge {network.el_standard_bridge.contract_address}"
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

    log.info(f"[E] Get logs of {block_hash}")
    block_logs = network.w3.eth.get_logs(
        # TODO: filter params changed
        FilterParams(
            blockHash=block_hash,
            address=network.el_standard_bridge.contract_address,
        )
    )
    filtered_logs = block_logs  # TODO:
    log.info(f"Block logs: {filtered_logs}")
    # [15:51:33] INFO - transfer-multiple-asset.py - [E] Get logs of b'\xdf\xd1\xec\xa5%\xae\x11\\\x1f\xe3;4\xc4\xbdF)\xefwK\xe44\xcd0~\x19\xd9\x91\xc5(\xb2\x9cV'
    # [15:51:33] INFO - transfer-multiple-asset.py - Block logs: [
    #   AttributeDict({
    #     'address': '0x42699A7612A82f1d9C36148af9C77354759b210b',
    #     'topics': [
    #       HexBytes('0xa1c5906bb7b8a0c21149c912eb63f5b7f8a4ea3b1b46f5538f8c5936cdf963f4'),
    #       HexBytes('0x000000000000000000000000b9a219631aed55ebc3d998f17c3840b7ec39c0cc'),
    #       HexBytes('0x000000000000000000000000fe3b557e8fb62b89f4916b721be55ceb828dbd73'),
    #       HexBytes('0x000000000000000000000000dc1c695f29d77b4ea04281c4833730f5efd0209f')
    #     ],
    #     'data': HexBytes('0x00000000000000000000000000000000000000000000000000000000000f4240'),
    #     'blockNumber': 8,
    #     'transactionHash': HexBytes('0x71c69f4545463e803d8324f553b1cb85059c27ddc77d5bad179f23f18011fbaa'),
    #     'transactionIndex': 0,
    #     'blockHash': HexBytes('0xdfd1eca525ae115c1fe33b34c4bd4629ef774be434cd307e19d991c528b29c56'),
    #     'logIndex': 1,
    #     'removed': False
    #   }),
    #   AttributeDict({'address': '0x42699A7612A82f1d9C36148af9C77354759b210b', 'topics': [HexBytes('0xa1c5906bb7b8a0c21149c912eb63f5b7f8a4ea3b1b46f5538f8c5936cdf963f4'), HexBytes('0x000000000000000000000000b9a219631aed55ebc3d998f17c3840b7ec39c0cc'), HexBytes('0x000000000000000000000000f17f52151ebef6c7334fad080c5704d77216b732'), HexBytes('0x000000000000000000000000dc1c695f29d77b4ea04281c4833730f5efd0209f')], 'data': HexBytes('0x00000000000000000000000000000000000000000000000000000000001e8480'), 'blockNumber': 8, 'transactionHash': HexBytes('0xe9085a213657da78127a7860c7796d7af76e1f60a2515c89861081ee81799c50'), 'transactionIndex': 1, 'blockHash': HexBytes('0xdfd1eca525ae115c1fe33b34c4bd4629ef774be434cd307e19d991c528b29c56'), 'logIndex': 3, 'removed': False}),
    #   AttributeDict({'address': '0x42699A7612A82f1d9C36148af9C77354759b210b', 'topics': [HexBytes('0xa1c5906bb7b8a0c21149c912eb63f5b7f8a4ea3b1b46f5538f8c5936cdf963f4'), HexBytes('0x000000000000000000000000b9a219631aed55ebc3d998f17c3840b7ec39c0cc'), HexBytes('0x000000000000000000000000fe3b557e8fb62b89f4916b721be55ceb828dbd73'), HexBytes('0x000000000000000000000000dc1c695f29d77b4ea04281c4833730f5efd0209f')], 'data': HexBytes('0x00000000000000000000000000000000000000000000000000000000000f4240'), 'blockNumber': 8, 'transactionHash': HexBytes('0x012e9d8b9d07eb9efca8df762d8286f17137792126c182fa2251057762ea02e8'), 'transactionIndex': 2, 'blockHash': HexBytes('0xdfd1eca525ae115c1fe33b34c4bd4629ef774be434cd307e19d991c528b29c56'), 'logIndex': 5, 'removed': False}), AttributeDict({'address': '0x42699A7612A82f1d9C36148af9C77354759b210b', 'topics': [HexBytes('0xa1c5906bb7b8a0c21149c912eb63f5b7f8a4ea3b1b46f5538f8c5936cdf963f4'), HexBytes('0x000000000000000000000000b9a219631aed55ebc3d998f17c3840b7ec39c0cc'), HexBytes('0x000000000000000000000000f17f52151ebef6c7334fad080c5704d77216b732'), HexBytes('0x000000000000000000000000babf4e4a8e0e864a287c9e1370b756cb36a023b9')], 'data': HexBytes('0x00000000000000000000000000000000000000000000000000000000002dc6c0'), 'blockNumber': 8, 'transactionHash': HexBytes('0x6f337c29d8a351fdd692e1631c80ccdb6f4d0f3a9646956360017a1170f4e385'), 'transactionIndex': 3, 'blockHash': HexBytes('0xdfd1eca525ae115c1fe33b34c4bd4629ef774be434cd307e19d991c528b29c56'), 'logIndex': 7, 'removed': False})
    # ]

    merkle_leaves = []
    transfer_index_in_block = -1
    for i, x in enumerate(block_logs):
        evt = network.el_standard_bridge.parse_erc20_bridge_initiated(x)
        log.info(f"[E] Parsed event: {evt}")
        merkle_leaves.append(evt.to_merkle_leaf())
        # if x["transactionHash"] == transfer_txn_hash:
        #     transfer_index_in_block = i

    # E2CTransferParams(
    #     block_with_transfer_hash=block_hash,
    #     merkle_proofs=get_merkle_proofs(merkle_leaves, transfer_index_in_block),
    #     transfer_index_in_block=transfer_index_in_block,
    # )

    log.info("TOD0")
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
