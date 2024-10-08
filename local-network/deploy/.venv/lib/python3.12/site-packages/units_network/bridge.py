import json
import logging
from dataclasses import dataclass
from importlib.resources import files
from time import sleep
from typing import List, Tuple

from eth_account.signers.base import BaseAccount
from eth_typing import BlockNumber, HexStr
from eth_utils.abi import event_abi_to_log_topic
from hexbytes import HexBytes
from web3 import Web3
from web3.exceptions import BlockNotFound
from web3.types import FilterParams, Nonce, TxParams, Wei

from units_network.merkle import get_merkle_proofs


@dataclass()
class E2CTransferParams:
    block_with_transfer_hash: HexBytes
    merkle_proofs: List[str]
    transfer_index_in_block: int


class Bridge(object):
    def __init__(self, w3: Web3, el_bridge_address: str):
        self.log = logging.getLogger(__class__.__name__)
        self.w3 = w3
        self.address = Web3.to_checksum_address(el_bridge_address)

        el_bridge_abi_text = (
            files("units_network").joinpath("bridge-abi.json").read_text()
        )
        el_bridge_abi = json.loads(el_bridge_abi_text)

        self.contract = self.w3.eth.contract(
            address=self.address,
            abi=el_bridge_abi,
        )

        self.topic = HexStr(
            event_abi_to_log_topic(self.contract.events.SentNative().abi).hex()
        )

    def send_native(
        self,
        from_eth_account: BaseAccount,
        to_waves_pk_hash: bytes,
        amount: Wei,
        gas_price: Wei = Wei(-1),
        nonce: Nonce = Nonce(-1),
    ) -> HexBytes:
        gas_price = self.w3.eth.gas_price if gas_price < 0 else gas_price
        nonce = (
            self.w3.eth.get_transaction_count(from_eth_account.address)
            if (nonce < 0)
            else nonce
        )
        txn: TxParams = {
            "from": from_eth_account.address,
            "nonce": nonce,
            "gasPrice": gas_price,
            "value": amount,
        }
        send_native_call = self.contract.functions.sendNative(to_waves_pk_hash)
        gas = send_native_call.estimate_gas(txn)
        txn.update(
            {
                "to": self.contract.address,
                "gas": gas,
                "data": send_native_call._encode_transaction_data(),
            }
        )
        signed_tx = from_eth_account.sign_transaction(txn)  # type: ignore
        self.log.debug(f"Signed Bridge.sendNative transaction: {Web3.to_json(signed_tx)}")  # type: ignore

        return self.w3.eth.send_raw_transaction(signed_tx.raw_transaction)

    def get_transfer_params(
        self, block_hash: HexBytes, transfer_txn_hash: HexBytes
    ) -> E2CTransferParams:
        block_hash_hex = block_hash.hex()
        block_logs = self.w3.eth.get_logs(
            FilterParams(
                blockHash=block_hash,
                address=self.address,
                topics=[self.topic],
            )
        )

        self.log.debug(
            f"Bridge logs in block 0x{block_hash_hex} by topic '0x{self.topic}': {Web3.to_json(block_logs)}"  # type: ignore
        )

        merkle_leaves = []
        transfer_index_in_block = -1
        for i, x in enumerate(block_logs):
            merkle_leaves.append(x["data"].hex())
            if x["transactionHash"] == transfer_txn_hash:
                transfer_index_in_block = i

        return E2CTransferParams(
            block_with_transfer_hash=block_hash,
            merkle_proofs=get_merkle_proofs(merkle_leaves, transfer_index_in_block),
            transfer_index_in_block=transfer_index_in_block,
        )

    def wait_for_withdrawals(
        self,
        from_height: BlockNumber,
        expected_withdrawals: List[Tuple[BaseAccount, Wei]],
    ):
        missing = len(expected_withdrawals)
        while True:
            try:
                curr_block = self.w3.eth.get_block(from_height)
                assert "number" in curr_block and "hash" in curr_block

                if curr_block:
                    withdrawals = curr_block.get("withdrawals", [])
                    self.log.debug(
                        f"Found block #{curr_block['number']}: 0x{curr_block['hash'].hex()} with withdrawals: {Web3.to_json(withdrawals)}"  # type: ignore
                    )
                    for w in withdrawals:
                        withdrawal_address = w["address"].lower()
                        withdrawal_amount = Web3.to_wei(w["amount"], "gwei")
                        for i, (el_account, wei_amount) in enumerate(
                            expected_withdrawals
                        ):
                            if (
                                withdrawal_address == el_account.address.lower()
                                and withdrawal_amount == wei_amount
                            ):
                                self.log.info(f"Found an expected withdrawal: {w}")
                                missing -= 1
                                del expected_withdrawals[i]
                                break

                    if missing <= 0:
                        self.log.debug("Found all withdrawals")
                        break

                    from_height = BlockNumber(from_height + 1)
            except BlockNotFound:
                pass

            sleep(2)
