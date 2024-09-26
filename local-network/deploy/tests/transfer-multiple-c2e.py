#!/usr/bin/env python
# Multiple C2E transfers
import os
from dataclasses import dataclass
from functools import cached_property
from time import sleep
from typing import List

from eth_account.signers.local import LocalAccount
from eth_typing import BlockNumber, ChecksumAddress
from pywaves import pw
from units_network import common_utils
from web3 import Web3
from web3.exceptions import BlockNotFound
from web3.types import Wei

from local.accounts import accounts
from local.network import get_network


@dataclass()
class C2ETransfer:
    el_account: LocalAccount
    cl_account: pw.Address
    raw_amount: float

    @cached_property
    def wei_amount(self) -> Wei:
        return Web3.to_wei(self.raw_amount, "ether")

    @cached_property
    def waves_atomic_amount(self) -> int:
        # Issued token has 8 decimals, we need to calculate amount in atomic units https://docs.waves.tech/en/blockchain/token/#atomic-unit
        return int(float(self.raw_amount) * 10**8)

    def __repr__(self) -> str:
        return f"C2E(from={self.cl_account.address}, to={self.el_account.address}, {self.raw_amount} UNIT0)"


@dataclass()
class ExpectedBalance:
    balance_before: Wei
    expected_transfer: Wei


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

    expected_balances: dict[ChecksumAddress, ExpectedBalance] = {}
    for i, t in enumerate(transfers):
        el_address = t.el_account.address
        if el_address in expected_balances:
            x = expected_balances[el_address]
            x.expected_transfer = Wei(x.expected_transfer + t.wei_amount)
        else:
            balance_before = network.w3.eth.get_balance(el_address)
            expected_balances[el_address] = ExpectedBalance(
                balance_before=balance_before, expected_transfer=t.wei_amount
            )
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

    def el_wait_for_withdraw(
        from_height: BlockNumber,
        transfers: List[C2ETransfer],
    ):
        missing_transfers = len(transfers)
        while True:
            try:
                curr_block = network.w3.eth.get_block(from_height)
                assert "number" in curr_block and "hash" in curr_block

                if curr_block:
                    withdrawals = curr_block.get("withdrawals", [])
                    log.info(
                        f"[E] Found block #{curr_block['number']}: 0x{curr_block['hash'].hex()} with withdrawals: {Web3.to_json(withdrawals)}"  # type: ignore
                    )
                    for w in withdrawals:
                        withdrawal_address = w["address"].lower()
                        withdrawal_amount = Web3.to_wei(w["amount"], "gwei")
                        for t in transfers:
                            if (
                                withdrawal_address == t.el_account.address.lower()
                                and withdrawal_amount == t.wei_amount
                            ):
                                log.info(f"[E] Found transfer {t}: {w}")
                                missing_transfers -= 1

                    if missing_transfers <= 0:
                        log.info("[E] Found all transfers")
                        break

                    from_height = BlockNumber(from_height + 1)
            except BlockNotFound:
                pass

            sleep(2)

    el_wait_for_withdraw(el_curr_height, transfers)

    for el_address, x in expected_balances.items():
        balance_after = network.w3.eth.get_balance(el_address)
        log.info(f"[E] {el_address} balance after: {balance_after / 10**18} UNIT0")

        assert balance_after == Wei(x.balance_before + x.expected_transfer)

    log.info("Done")


if __name__ == "__main__":
    main()
