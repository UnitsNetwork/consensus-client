import json
import os
from dataclasses import dataclass

from eth_typing import ChecksumAddress


@dataclass()
class Deployments:
    standard_bridge: ChecksumAddress
    wwaves: ChecksumAddress
    terc20: ChecksumAddress

    @staticmethod
    def load() -> "Deployments":
        contracts_dir = os.getenv("CONTRACTS_DIR") or os.path.join(
            os.getcwd(), "..", "..", "contracts", "eth"
        )
        deploy_path = os.path.join(
            contracts_dir, "target", "deployments", "1337", ".deploy"
        )
        with open(deploy_path, "r") as deployFile:
            deployments = json.load(deployFile)
            return Deployments(**deployments)
