from web3 import Web3
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
