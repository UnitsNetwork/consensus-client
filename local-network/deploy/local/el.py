from logging import Logger
from time import sleep
from typing import List

from eth_typing import BlockNumber
from web3 import Web3
from web3.exceptions import BlockNotFound

from local.common import C2ETransfer


def el_wait_for_withdraw(
    log: Logger,
    w3: Web3,
    from_height: BlockNumber,
    transfers: List[C2ETransfer],
):
    missing_transfers = len(transfers)
    while True:
        try:
            curr_block = w3.eth.get_block(from_height)
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
