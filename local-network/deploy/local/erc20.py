from web3 import Web3
from web3.types import Wei

from local.BaseContract import BaseContract


class Erc20(BaseContract):
    def decimals(self):
        return self.contract.functions.decimals().call()

    def get_balance(self, address) -> Wei:
        address = Web3.to_checksum_address(address)
        return self.contract.functions.balanceOf(address).call()

    def approve(self, spender_address, amount: Wei, sender_account):
        return self.send_transaction(
            "approve",
            [spender_address, amount],
            sender_account,
        )

    def transfer(self, to_address, amount: Wei, sender_account):
        return self.send_transaction("transfer", [to_address, amount], sender_account)
