#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

SOLC_VERSION=0.8.26
solc-select use $SOLC_VERSION --always-install

echo "Compile bridge.sol"
# abi to run contract functions
# bin-runtime is "code" field in genesis.json
# storage-layout helps to fill "storage" field in genesis.json
solc --abi --bin --bin-runtime --storage-layout ./setup/el/bridge.sol --optimize --optimize-runs 200 --overwrite -o ./setup/el/compiled/
