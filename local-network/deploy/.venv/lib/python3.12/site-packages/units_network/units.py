from decimal import Decimal
from web3 import Web3
from web3.types import Wei


def raw_to_wei(raw_amount: Decimal) -> Wei:
    return Web3.to_wei(raw_amount, "ether")


def wei_to_raw(amount: Wei) -> int | Decimal:
    return Web3.from_wei(amount, "ether")


def raw_to_waves_atomic(raw_amount: Decimal, asset_decimals: int = 8) -> int:
    # Issued token has 8 decimals, we need to calculate amount in atomic units https://docs.waves.tech/en/blockchain/token/#atomic-unit
    return int(raw_amount.scaleb(asset_decimals))


def waves_atomic_to_raw(amount: int, asset_decimals: int = 8) -> Decimal:
    return Decimal(amount).scaleb(-asset_decimals)
