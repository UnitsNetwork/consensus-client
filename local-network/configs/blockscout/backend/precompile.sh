#!/usr/bin/env bash

if [ -z "$1" ]; then
  echo "Usage: $0 </path/to/contract.sol>"
  exit 1
fi

CONTRACT_PATH=$1

# +4 to skip heading garbage: https://github.com/ethereum/solidity/issues/10275
jq -n \
  --arg bytecode "0x$(solc --bin-runtime "$CONTRACT_PATH" | tail -n +4)" \
  --arg source "$(jq -Rs . < "$CONTRACT_PATH")" \
  --arg abi "$(solc --pretty-json --abi "$CONTRACT_PATH" | tail -n +4 | jq -c .)" \
  '{bytecode: $bytecode, source: $source, abi: $abi}'
