import json
import rlp
from web3 import Web3
from web3.types import Wei


def compute_contract_address(sender_address, nonce):
    sender_bytes = bytes.fromhex(sender_address[2:])
    computed = Web3.keccak(rlp.encode([sender_bytes, nonce]))
    return Web3.to_checksum_address(computed[-20:])


class ERC20:
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
            receipt = ERC20.deploy(w3, account, compiled_contract)
            deployed_address = receipt.contractAddress

            if deployed_address != contract_address:
                raise ValueError(
                    f"Contract address mismatch: expected {contract_address}, got {deployed_address}"
                )

        return ERC20(w3, contract_address, compiled_contract["abi"])

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

    def __init__(self, w3, contract_address, compiled_contract_abi):
        self.w3 = w3
        self.abi = compiled_contract_abi
        self.contract_address = contract_address
        self.contract = self.w3.eth.contract(
            address=self.contract_address, abi=self.abi
        )

    def decimals(self):
        return self.contract.functions.decimals().call()

    def get_balance(self, address) -> Wei:
        """Returns the token balance of a given address."""
        address = Web3.to_checksum_address(address)
        return self.contract.functions.balanceOf(address).call()

    def approve(self, amount: Wei, sender_account):
        """Approves the sender account to spend a given amount of tokens."""
        nonce = self.w3.eth.get_transaction_count(
            sender_account.address, block_identifier="pending"
        )
        tx = self.contract.functions.approve(
            sender_account.address, amount
        ).build_transaction(
            {
                "from": sender_account.address,
                "nonce": nonce,
                "gasPrice": self.w3.eth.gas_price,
            }
        )

        signed_tx = sender_account.sign_transaction(tx)
        tx_hash = self.w3.eth.send_raw_transaction(signed_tx.raw_transaction)
        return self.w3.to_hex(tx_hash)

    def transfer(self, to_address, amount: Wei, sender_account):
        """Transfers tokens to another address."""
        to_address = Web3.to_checksum_address(to_address)

        nonce = self.w3.eth.get_transaction_count(
            sender_account.address, block_identifier="pending"
        )
        tx = self.contract.functions.transfer(to_address, amount).build_transaction(
            {
                "from": sender_account.address,
                "nonce": nonce,
                "gasPrice": self.w3.eth.gas_price,
            }
        )

        signed_tx = sender_account.sign_transaction(tx)
        tx_hash = self.w3.eth.send_raw_transaction(signed_tx.raw_transaction)
        return self.w3.to_hex(tx_hash)
