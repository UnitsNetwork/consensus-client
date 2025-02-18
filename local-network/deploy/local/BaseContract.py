class BaseContract:
    def __init__(self, w3, contract_address, abi):
        self.w3 = w3
        self.abi = abi
        self.contract_address = contract_address
        self.contract = self.w3.eth.contract(
            address=self.contract_address, abi=self.abi
        )

    def send_transaction(self, function_name, args, sender_account):
        nonce = self.w3.eth.get_transaction_count(sender_account.address, "pending")
        tx = getattr(self.contract.functions, function_name)(*args).build_transaction(
            {
                "from": sender_account.address,
                "nonce": nonce,
                "gasPrice": self.w3.eth.gas_price,
            }
        )
        signed_tx = sender_account.sign_transaction(tx)
        tx_hash = self.w3.eth.send_raw_transaction(signed_tx.raw_transaction)
        return self.w3.to_hex(tx_hash)
