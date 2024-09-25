#!/usr/bin/env python
import os

from units_network import common_utils

from local.accounts import accounts
from local.network import get_network


def main():
    log = common_utils.configure_script_logger(os.path.basename(__file__))
    network = get_network()

    cl_account = accounts.waves_miners[0].account
    el_account = network.w3.eth.account.from_key(
        "0x6f077b245cb0e1ec59796366ebd3a254e604cf5686b64b7878ae730eb9ad9570"
    )

    user_amount = "0.01"
    # Issued token has 8 decimals, we need to calculate amount in atomic units https://docs.waves.tech/en/blockchain/token/#atomic-unit
    atomic_amount = int(float(user_amount) * 10**8)

    log.info(
        f"Sending {user_amount} Unit0 ({atomic_amount} in atomic units) from {cl_account.address} (C) to {el_account.address} (E)"
    )

    token = network.cl_chain_contract.getToken()
    log.info(f"[C] Token id: {token.assetId}")

    # TODO el_account -> el_address
    transfer_result = network.cl_chain_contract.transfer(
        cl_account, el_account, token, atomic_amount
    )
    log.info(f"[C] Transfer result: {transfer_result}")
    log.info("Done")


if __name__ == "__main__":
    main()
