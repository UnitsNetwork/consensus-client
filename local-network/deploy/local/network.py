from typing import Optional, Tuple

from units_network.networks import Network, NetworkSettings

from local.accounts import Accounts
from local.common import in_docker


def get_waves_api_url(n: int) -> str:
    return f"http://wavesnode-{n}:6869" if in_docker() else f"http://127.0.0.1:{n}6869"


def get_ec_api_url(n: int) -> str:
    return f"http://ec-{n}:8545" if in_docker() else f"http://127.0.0.1:{n}8545"


local_net = NetworkSettings(
    name="LocalNet",
    chain_id_str="D",
    cl_node_api_url=get_waves_api_url(1),
    el_node_api_url=get_ec_api_url(1),
    chain_contract_address="3FdaanzgX4roVgHevhq8L8q42E7EZL9XTQr",  # TODO: do it after pw.setNode accounts.chain_contract.address,
)


_NETWORK_WITH_ACCOUNTS: Optional[Tuple[Network, Accounts]] = None


def get_local() -> Tuple[Network, Accounts]:
    global _NETWORK_WITH_ACCOUNTS
    if _NETWORK_WITH_ACCOUNTS is None:
        n = Network.create_manual(local_net)
        a = Accounts(n)
        _NETWORK_WITH_ACCOUNTS = (n, a)

    return _NETWORK_WITH_ACCOUNTS
