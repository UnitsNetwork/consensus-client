#!/usr/bin/env sh

IP=$(hostname -I)

# p2p-host = "ec-1" # Doesn't work: https://github.com/hyperledger/besu/issues/4380
besu \
  --config-file=/config/besu.conf \
  --p2p-host=${IP}
