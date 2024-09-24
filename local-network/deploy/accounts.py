from dataclasses import dataclass
from typing import List, TypedDict
from pywaves import address, pw
import requests
from functools import cached_property

# Prevent of aliases lookup
pw.setOffline()


class ExtendedAddress(pw.Address):
    def scriptInfo(self):
        url = f"{self.pywaves.NODE}/addresses/scriptInfo/{self.address}"
        print(url)
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
        print("chain_contract")
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


accounts = Accounts()

pw.setOnline()
