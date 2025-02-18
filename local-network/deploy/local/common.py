import os
from dataclasses import dataclass
from decimal import Decimal
from functools import cached_property

import rlp
from eth_account.signers.local import LocalAccount
from pywaves import pw
from units_network import units
from web3 import Web3
from web3.types import Wei


def compute_contract_address(sender_address, nonce):
    sender_bytes = bytes.fromhex(sender_address[2:])
    computed = Web3.keccak(rlp.encode([sender_bytes, nonce]))
    return Web3.to_checksum_address(computed[-20:])


@dataclass()
class BaseTransfer:
    el_account: LocalAccount
    cl_account: pw.Address
    raw_amount: Decimal

    @cached_property
    def wei_amount(self) -> Wei:
        return units.raw_to_wei(self.raw_amount)

    @cached_property
    def waves_atomic_amount(self) -> int:
        return units.raw_to_waves_atomic(self.raw_amount)


@dataclass()
class C2ETransfer(BaseTransfer):
    @property
    def from_account(self) -> pw.Address:
        return self.cl_account

    @property
    def to_account(self) -> LocalAccount:
        return self.el_account

    def __repr__(self) -> str:
        return f"C2E(from={self.cl_account.address}, to={self.el_account.address}, {self.raw_amount} UNIT0)"


@dataclass()
class E2CTransfer(BaseTransfer):
    @property
    def from_account(self) -> LocalAccount:
        return self.el_account

    @property
    def to_account(self) -> pw.Address:
        return self.cl_account

    def __repr__(self) -> str:
        return f"E2C(from={self.el_account.address}, to={self.cl_account.address}, {self.raw_amount} UNIT0)"


_INSIDE_DOCKER = None


def in_docker() -> bool:
    global _INSIDE_DOCKER
    if _INSIDE_DOCKER is None:
        try:
            os.stat("/.dockerenv")
            _INSIDE_DOCKER = True
        except FileNotFoundError:
            _INSIDE_DOCKER = False
    return _INSIDE_DOCKER
