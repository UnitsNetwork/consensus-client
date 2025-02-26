import json

from local.common import compute_contract_address


class ContractFactory:
    @staticmethod
    def maybe_deploy(w3, account, compiled_contract_path):
        """
        Connects to an existing contract or deploys it if not yet deployed.
        """
        with open(compiled_contract_path, "r") as file:
            compiled_contract = json.load(file)

        contract_address = compute_contract_address(account.address, 0)
        code = w3.eth.get_code(contract_address)
        if not code or code == b"":
            receipt = ContractFactory.deploy(w3, account, 0, compiled_contract)
            deployed_address = receipt.contractAddress

            if deployed_address != contract_address:
                raise ValueError(
                    f"Contract address mismatch: expected {contract_address}, got {deployed_address}"
                )

    @staticmethod
    def deploy(w3, account, nonce, compiled_contract):
        """
        The JSON is expected to have the bytecode at the JSON path "bytecode/object" (a 0x-prefixed hex string).
        """
        bytecode = compiled_contract["bytecode"]["object"]

        tx = {
            "from": account.address,
            "nonce": nonce,
            "gasPrice": w3.eth.gas_price,
            "data": bytecode,
            "chainId": w3.eth.chain_id,
        }
        tx["gas"] = w3.eth.estimate_gas(tx)

        signed_tx = account.sign_transaction(tx)
        tx_hash = w3.eth.send_raw_transaction(signed_tx.raw_transaction)
        return w3.eth.wait_for_transaction_receipt(tx_hash)
