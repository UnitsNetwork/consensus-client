#!/usr/bin/env python
# One C2E transfer
import os
from time import sleep

from eth_account.signers.local import LocalAccount
from eth_typing import BlockNumber, HexAddress
from units_network import common_utils
from web3 import Web3
from web3.exceptions import BlockNotFound

from local.accounts import accounts
from local.network import get_network


def main():
    log = common_utils.configure_script_logger(os.path.basename(__file__))
    network = get_network()

    from local import waves_txs

    cl_account = accounts.waves_miners[0].account
    el_account: LocalAccount = network.w3.eth.account.from_key(
        "0x6f077b245cb0e1ec59796366ebd3a254e604cf5686b64b7878ae730eb9ad9570"
    )

    balance_before = network.w3.eth.get_balance(el_account.address)
    log.info(f"Balance before: {balance_before / 10**18} UNIT0")

    raw_amount = "0.01"
    # Issued token has 8 decimals, we need to calculate amount in atomic units https://docs.waves.tech/en/blockchain/token/#atomic-unit
    atomic_amount = int(float(raw_amount) * 10**8)

    log.info(
        f"Sending {raw_amount} Unit0 ({atomic_amount} in atomic units) from {cl_account.address} (C) to {el_account.address} (E)"
    )

    token = network.cl_chain_contract.getToken()
    log.info(f"[C] Token id: {token.assetId}")

    el_curr_height = network.w3.eth.block_number
    transfer_result = network.cl_chain_contract.transfer(
        cl_account, el_account, token, atomic_amount
    )
    waves_txs.force_success(
        log, transfer_result, "Can not send the chain_contract.transfer transaction"
    )
    log.info(f"[C] Transfer result: {transfer_result}")

    def el_wait_for_withdraw(
        from_height: BlockNumber, address: HexAddress, min_amount: int
    ):
        while True:
            try:
                curr_block = network.w3.eth.get_block(from_height)
                assert "number" in curr_block and "hash" in curr_block

                if curr_block:
                    withdrawals = curr_block.get("withdrawals", [])
                    log.info(
                        f"Found block #{curr_block['number']}: 0x{curr_block['hash'].hex()} with withdrawals: {Web3.to_json(withdrawals)}"  # type: ignore
                    )
                    for w in withdrawals:
                        if (
                            w["address"].lower() == address.lower()
                            and w["amount"] >= min_amount
                        ):
                            return
                    from_height = BlockNumber(from_height + 1)
            except BlockNotFound:
                pass

            sleep(2)

    el_wait_for_withdraw(el_curr_height, el_account.address, atomic_amount)
    balance_after = network.w3.eth.get_balance(el_account.address)
    log.info(f"Balance after: {balance_after / 10**18} UNIT0")

    assert balance_after == (balance_before + int(float(raw_amount) * 10**18))
    log.info("Done")


if __name__ == "__main__":
    main()
