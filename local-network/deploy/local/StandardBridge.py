from dataclasses import dataclass

from eth_typing import HexAddress
from web3 import Web3
from web3.types import LogReceipt

from local.BaseContract import BaseContract


class StandardBridge(BaseContract):

    def bridge_erc20(self, token, cl_to, el_amount, sender_account):
        return self.send_transaction(
            "bridgeERC20",
            [Web3.to_checksum_address(token), cl_to, el_amount],
            sender_account,
        )

    def finalize_bridge_erc20(self, local_token, from_addr, to, amount, sender_account):
        return self.send_transaction(
            "finalizeBridgeERC20", [local_token, from_addr, to, amount], sender_account
        )

    def token_ratio(self, address):
        return self.contract.functions.tokenRatios(address).call()

    def parse_erc20_bridge_initiated(self, log: LogReceipt):
        args = self.contract.events.ERC20BridgeInitiated().process_log(log)["args"]
        return ERC20BridgeInitiatedEvent(
            local_token=HexAddress(args["localToken"]),
            from_address=HexAddress(args["from"]),
            cl_to=HexAddress(args["clTo"]),
            cl_amount=args["clAmount"],
        )


@dataclass
class ERC20BridgeInitiatedEvent:
    local_token: HexAddress
    from_address: HexAddress
    cl_to: HexAddress
    cl_amount: int

    def to_merkle_leaf(self) -> bytes:
        local_token_bytes = bytes.fromhex(self.local_token[2:].zfill(40))
        cl_to_bytes = bytes.fromhex(self.cl_to[2:].zfill(40))
        cl_amount_bytes = self.cl_amount.to_bytes(32, byteorder="big", signed=False)
        return local_token_bytes + cl_to_bytes + cl_amount_bytes
