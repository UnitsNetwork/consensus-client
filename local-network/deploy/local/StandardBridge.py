from local.BaseContract import BaseContract


class StandardBridge(BaseContract):
    def bridge_erc20(self, token, cl_to, el_amount, sender_account):
        return self.send_transaction(
            "bridgeERC20", [token, cl_to, el_amount], sender_account
        )

    def finalize_bridge_erc20(self, local_token, from_addr, to, amount, sender_account):
        return self.send_transaction(
            "finalizeBridgeERC20", [local_token, from_addr, to, amount], sender_account
        )
