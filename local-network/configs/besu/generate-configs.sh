#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

docker run -it --rm -v ${DIR}:/result hyperledger/besu:latest \
  operator generate-blockchain-config \
  --config-file=/result/network-config.json \
  --to=/result/generated \
  --private-key-file-name=key
