#!/usr/bin/env python
import sys
from decimal import Decimal

import pywaves as pw

from units_network import common_utils, networks, units


def main():
    log = common_utils.configure_cli_logger(__file__)

    cl_account_private_key = common_utils.get_argument_value("--waves-private-key")
    el_account_private_key = common_utils.get_argument_value("--eth-private-key")
    chain_id_str = common_utils.get_argument_value("--chain-id") or "S"
    user_amount = Decimal(common_utils.get_argument_value("--amount") or "0.01")

    if not (
        cl_account_private_key
        and el_account_private_key
        and el_account_private_key.startswith("0x")
        and user_amount > 0
    ):
        print(
            """Transfer native tokens from Consensus Layer (Waves) to Execution Layer (Ethereum).
Required arguments:
  transfer-c2e.py --waves-private-key <Waves private key in base58> --eth-private-key <Ethereum private key in HEX with 0x>
Additional optional arguments:
  --chain-id <S|T|W> (default: S): S - StageNet, T - TestNet, Not supported for now: W - MainNet 
  --amount N (default: 0.01): amount of transferred Unit0 tokens""",
            file=sys.stderr,
        )
        sys.exit(1)

    network = networks.select(chain_id_str)

    cl_account = pw.Address(privateKey=cl_account_private_key)
    el_account = network.w3.eth.account.from_key(el_account_private_key)

    atomic_amount = units.raw_to_waves_atomic(user_amount)

    log.info(
        f"Sending {user_amount} Unit0 ({atomic_amount} in atomic units) from {cl_account.address} (C) to {el_account.address} (E)"
    )

    token = network.cl_chain_contract.getToken()
    log.info(f"[C] Token id: {token.assetId}")

    transfer_result = network.cl_chain_contract.transfer(
        cl_account, el_account.address, token, atomic_amount
    )
    log.info(f"[C] ChainContract.transfer result: {transfer_result}")
    log.info("Done")


if __name__ == "__main__":
    main()
