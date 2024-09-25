from dataclasses import dataclass
from functools import cached_property
from typing import List

import requests
from eth_account.signers.local import LocalAccount
from pywaves import pw
from web3 import Account

# Prevent of aliases lookup
pw.setOffline()


class ExtendedAddress(pw.Address):
    def scriptInfo(self):
        url = f"{self.pywaves.NODE}/addresses/scriptInfo/{self.address}"
        response = requests.get(url)

        if response.status_code == 200:
            return response.json()
        else:
            raise Exception(f"Error: {response.status_code}, {response.text}")


@dataclass
class Miner:
    account: pw.Address
    el_reward_address_hex: str


class Accounts(object):
    @cached_property
    def chain_contract(self) -> ExtendedAddress:
        return ExtendedAddress(
            seed="devnet-1",
            nonce=2,
        )

    @cached_property
    def waves_miners(self) -> List[Miner]:
        return [
            Miner(
                account=pw.Address(
                    seed="devnet-1",
                    nonce=0,
                ),
                el_reward_address_hex="0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f9",
            ),
            Miner(
                account=pw.Address(
                    seed="devnet-2",
                    nonce=0,
                ),
                el_reward_address_hex="0xcf0b9e13fdd593f4ca26d36afcaa44dd3fdccbed",
            ),
        ]

    @cached_property
    def cl_rich_accounts(self) -> List[pw.Address]:
        return [pw.Address(seed="devnet-0", nonce=n) for n in range(0, 2)]

    @cached_property
    def el_rich_accounts(self) -> List[LocalAccount]:
        return [
            Account.from_key(
                "0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63"
            ),
            Account.from_key(
                "0xae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f"
            ),
        ]


accounts = Accounts()

pw.setOnline()
