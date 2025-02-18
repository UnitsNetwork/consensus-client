import json

from local.common import compute_contract_address
from local.Erc20 import Erc20
from local.StandardBridge import StandardBridge


class ContractFactory:
    @staticmethod
    def connect(w3, account, compiled_contract_path):
        """
        Connects to an existing contract or deploys it if not yet deployed.
        """
        with open(compiled_contract_path, "r") as file:
            compiled_contract = json.load(file)

        nonce = w3.eth.get_transaction_count(account.address, "pending")
        contract_address = compute_contract_address(account.address, nonce)

        code = w3.eth.get_code(contract_address)
        if not code or code == b"":
            receipt = ContractFactory.deploy(w3, account, compiled_contract)
            deployed_address = receipt.contractAddress

            if deployed_address != contract_address:
                raise ValueError(
                    f"Contract address mismatch: expected {contract_address}, got {deployed_address}"
                )

        return contract_address, compiled_contract["abi"]

    @staticmethod
    def deploy(w3, account, compiled_contract):
        """
        The JSON is expected to have the bytecode at the JSON path "bytecode/object" (a 0x-prefixed hex string).
        """
        bytecode = compiled_contract["bytecode"]["object"]

        nonce = w3.eth.get_transaction_count(account.address, "pending")
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

    @staticmethod
    def connect_standard_bridge(w3, account, compiled_contract_path):
        contract_address, abi = ContractFactory.connect(
            w3, account, compiled_contract_path
        )
        return StandardBridge(w3, contract_address, abi)

    @staticmethod
    def connect_erc20(w3, account, compiled_contract_path):
        contract_address, abi = ContractFactory.connect(
            w3, account, compiled_contract_path
        )
        return Erc20(w3, contract_address, abi)
