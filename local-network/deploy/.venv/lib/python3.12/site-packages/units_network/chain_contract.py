import logging
from time import sleep
from typing import List, Optional

import pywaves as pw
from eth_typing import HexAddress, HexStr
from pywaves.address import TxSigner
from pywaves.txGenerator import TxGenerator
from web3 import Web3
from web3.types import Wei

import units_network.exceptions
from units_network import common_utils
from units_network.extended_address import ExtendedAddress
from units_network.extended_oracle import ExtendedOracle


class ContractBlock(object):
    def __init__(self, hash, meta):
        self.hash = hash
        self.chain_height = meta["_1"]["value"]
        self.epoch_number = meta["_2"]["value"]

    def __repr__(self) -> str:
        return (
            f"ContractBlock({self.hash}, h={self.chain_height}, e={self.epoch_number})"
        )


class ChainContract(ExtendedOracle):
    def __init__(self, oracleAddress=None, seed=None, nonce=0, pywaves=pw):
        # super().__init__(oracleAddress, seed, pywaves)  # Doesn't propagate nonce
        self.pw = pywaves
        if seed is None:
            self.oracleAddress = oracleAddress
        else:
            self.oracleAcc = ExtendedAddress(seed=seed, nonce=nonce)
            self.oracleAddress = self.oracleAcc.address
        self.log = logging.getLogger(self.__class__.__name__)

    def isContractSetup(self) -> bool:
        r = self.evaluate("isContractSetup()")
        return r and r["result"] and r["result"]["value"]

    def getToken(self) -> pw.Asset:
        r: str = self.getData("tokenId")
        return pw.Asset(r)  # type: ignore

    def getElBridgeAddress(self) -> str:
        return self.getData("elBridgeAddress")

    def waitForFinalized(
        self, block: ContractBlock, timeout: float = 30, poll_latency: float = 2
    ):
        last_finalized_block: List[Optional[ContractBlock]] = [None]

        rest_timeout = timeout
        while True:
            curr_finalized_block = self.getFinalizedBlock()
            message = f"Wait for {block.chain_height - curr_finalized_block.chain_height} blocks to finalize"
            if not (
                last_finalized_block[0]
                and last_finalized_block[0].hash == curr_finalized_block.hash
            ):
                last_finalized_block[0] = curr_finalized_block
                message = f"Current finalized block is {curr_finalized_block}"
            self.log.debug(message)
            if curr_finalized_block.chain_height >= block.chain_height:
                return

            rest_timeout -= poll_latency
            if rest_timeout <= 0:
                break

            sleep(poll_latency)
        raise units_network.exceptions.TimeExhausted(
            f"Block {block.hash} not finalized on contract in {timeout} seconds"
        )

    def waitForBlock(
        self, block_hash: str, timeout: float = 30, poll_latency: float = 2
    ) -> ContractBlock:
        self.log.debug(f"Wait for {block_hash} on chain contract")
        rest_timeout = timeout
        while True:
            try:
                return self.getBlockMeta(block_hash)
            except units_network.exceptions.BlockNotFound:
                pass

            rest_timeout -= poll_latency
            if rest_timeout <= 0:
                break

            sleep(poll_latency)
        raise units_network.exceptions.TimeExhausted(
            f"Block {block_hash} not found on contract in {timeout} seconds"
        )

    def getFinalizedBlock(self) -> ContractBlock:
        hash = self.getData("finalizedBlock")
        return self.getBlockMeta(hash)

    def getBlockMeta(self, hash: str) -> ContractBlock:
        if hash.startswith("0x"):
            hash = hash[2:]

        r = self.evaluate(f'blockMeta("{hash}")')
        try:
            meta = r["result"]["value"]
            return ContractBlock(hash, meta)
        except Exception:
            raise units_network.exceptions.BlockNotFound(hash)

    def setScript(self, script: str, txFee: int = 3_200_000):
        return self.oracleAcc.setScript(script, txFee)

    def setup(
        self,
        elGenesisBlockHash: HexStr,
        minerRewardInTokens: float = 2.0,
        txFee: int = 100_500_000,
    ):
        minerRewardInWei = int(minerRewardInTokens * 10**18)
        minerRewardInGwei = Web3.from_wei(minerRewardInWei, "gwei")
        return self.oracleAcc.invokeScript(
            dappAddress=self.oracleAddress,
            functionName="setup",
            params=[
                {
                    "type": "string",
                    "value": common_utils.clean_hex_prefix(elGenesisBlockHash),
                },
                {
                    "type": "integer",
                    "value": int(minerRewardInGwei),
                },
            ],
            txFee=txFee,
        )

    def join(
        self,
        miner: pw.Address,
        elRewardAddress: HexStr,
        txFee: int = 500_000,
    ):
        return miner.invokeScript(
            dappAddress=self.oracleAddress,
            functionName="join",
            params=[
                {
                    "type": "binary",
                    "value": f"base64:{common_utils.hex_to_base64(common_utils.clean_hex_prefix(elRewardAddress))}",
                },
            ],
            txFee=txFee,
        )

    def join_v2(
        self,
        miner: pw.Address,
        elRewardAddress: HexStr,
        txFee: int = 500_000,
    ):
        # Reward address in string
        return miner.invokeScript(
            dappAddress=self.oracleAddress,
            functionName="join",
            params=[
                {
                    "type": "string",
                    "value": elRewardAddress,
                },
            ],
            txFee=txFee,
        )

    def transfer(
        self,
        from_waves_account: pw.Address,
        to_eth_address: HexAddress,
        token: pw.Asset,
        atomic_amount: int,
        txFee: int = 500_000,
    ):
        return from_waves_account.invokeScript(
            dappAddress=self.oracleAddress,
            functionName="transfer",
            params=[
                {
                    "type": "string",
                    "value": common_utils.clean_hex_prefix(to_eth_address).lower(),
                }
            ],
            payments=[{"amount": atomic_amount, "assetId": token.assetId}],
            txFee=txFee,
        )

    def withdraw(
        self,
        sender: pw.Address,
        block_hash_with_transfer: str,
        merkle_proofs: List[str],
        transfer_index_in_block: int,
        amount: Wei,
        txFee: int = 500_000,
    ):
        txn = self.prepareWithdraw(
            sender,
            block_hash_with_transfer,
            merkle_proofs,
            transfer_index_in_block,
            amount,
            txFee,
        )
        return sender.broadcastTx(txn)

    def prepareWithdraw(
        self,
        sender: pw.Address,
        block_hash_with_transfer: str,
        merkle_proofs: List[str],
        transfer_index_in_block: int,
        amount: Wei,
        txFee: int = 500_000,
    ):
        generator = TxGenerator(self.pw)  # type: ignore
        signer = TxSigner(self.pw)  # type: ignore

        proofs = [
            {"type": "binary", "value": f"base64:{common_utils.hex_to_base64(p)}"}
            for p in merkle_proofs
        ]
        withdraw_amount = amount // (10**10)
        params = [
            {"type": "string", "value": block_hash_with_transfer},
            {"type": "list", "value": proofs},
            {"type": "integer", "value": transfer_index_in_block},
            {"type": "integer", "value": withdraw_amount},
        ]
        txn = generator.generateInvokeScript(
            publicKey=sender.publicKey,
            dappAddress=self.oracleAddress,
            functionName="withdraw",
            params=params,
            txFee=txFee,
        )
        signer.signTx(txn, privateKey=sender.privateKey)
        return txn
