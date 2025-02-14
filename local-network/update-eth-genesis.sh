#!/usr/bin/env bash
cd -- "$( dirname -- "${BASH_SOURCE[0]}" )"
forge build --config-path ../contracts/eth/foundry.toml
standard_bridge_contract_code=$(jq .deployedBytecode.object ../contracts/eth/target/StandardBridge.sol/StandardBridge.json)
predeployed_contracts=$(cat <<EOF
{
  "alloc": {
    "0x0000000000000000000000000000000057d06A7E": {
      "code": $standard_bridge_contract_code,
      "balance": "0x0"
    }
  }
}
EOF
)
rm -rf configs/ec-common/genesis.json
echo $predeployed_contracts | cat configs/ec-common/eth-genesis-template.json - | jq -s '.[0] * .[1]' > configs/ec-common/genesis.json
