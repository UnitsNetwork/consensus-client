#!/usr/bin/env bash

forge build --config-path ../contracts/eth/foundry.toml ../contracts/eth/src ../contracts/eth/utils/TERC20.sol
bridge_contract_code=$(jq .deployedBytecode.object ../contracts/eth/target/Bridge.sol/Bridge.json)
standard_bridge_contract_code=$(jq .deployedBytecode.object ../contracts/eth/target/StandardBridge.sol/StandardBridge.json)
erc20_contract_code=$(jq .deployedBytecode.object ../contracts/eth/target/TERC20.sol/TERC20.json)
predeployed_contracts=$(cat <<EOF
{
  "alloc": {
    "0x0000000000000000000000000000000000006A7e": {
      "code": $bridge_contract_code,
      "balance": "0x0"
    },
    "0x0000000000000000000000000000000057d06A7E": {
      "code": $standard_bridge_contract_code,
      "balance": "0x0"
    },
    "0x0000000000000000000000000000000020202020": {
        "code": $erc20_contract_code,
        "balance": "0x0"
    }
  }
}
EOF
)
echo $predeployed_contracts | cat configs/ec-common/eth-genesis-template.json - | jq -s '.[0] * .[1]' > configs/ec-common/genesis.json