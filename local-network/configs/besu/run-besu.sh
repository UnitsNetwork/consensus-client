#!/usr/bin/env sh

IP=$(hostname -I)

tee /opt/besu/logs/besu.log <<EOF
IP: $IP
EOF

# --p2p-host="ec-1" # Doesn't work: https://github.com/hyperledger/besu/issues/4380
besu \
  --config-file=/config/besu.conf \
  --p2p-host=${IP} \
  --node-private-key-file="/etc/secrets/p2p-key-${NODE_NUMBER}.hex"
