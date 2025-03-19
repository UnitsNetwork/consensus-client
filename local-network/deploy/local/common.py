import os
from dataclasses import dataclass
from decimal import Decimal
from functools import cached_property

from eth_account.signers.base import BaseAccount
from pywaves import pw
from units_network import units
from web3.types import Wei


@dataclass()
class Transfer:
    cl_account: pw.Address
    el_account: BaseAccount

    user_amount: Decimal

    cl_asset: pw.Asset
    el_token_decimals: int

    @cached_property
    def cl_atomic_amount(self) -> int:
        return units.user_to_atomic(self.user_amount, self.cl_asset.decimals)

    @cached_property
    def el_atomic_amount(self) -> Wei:
        return Wei(units.user_to_atomic(self.user_amount, self.el_token_decimals))

    def __repr__(self) -> str:
        return f"Transfer(cl={self.cl_account.address}, el={self.el_account.address}, amount={self.user_amount} of {self.cl_asset.assetId}"


@dataclass()
class TransferBuilder:
    cl_asset: pw.Asset
    el_token_decimals: int

    def create(
        self, cl_account: pw.Address, el_account: BaseAccount, user_amount: Decimal
    ) -> Transfer:
        return Transfer(
            cl_account, el_account, user_amount, self.cl_asset, self.el_token_decimals
        )


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
