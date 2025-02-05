#!/usr/bin/env sh

if [ ! -d /root/.ethereum/geth ] ; then
  geth init --state.scheme=hash /etc/secrets/genesis.json 2>&1 | tee /root/logs/init.log
fi

IP_RAW=$(ip -4 addr show dev eth0 | awk '/inet / {print $2}')
IP=$(echo "$IP_RAW" | cut -d/ -f1)
NETWORK=$(echo "$IP_RAW" | xargs ipcalc -n | awk -F= '{print $2}')
PREFIX=$(echo "$IP_RAW" | xargs ipcalc -p | awk -F= '{print $2}')

LOG_FILE="/root/logs/geth.log"

tee /root/logs/log <<EOF
IP: $IP
NETWORK: $NETWORK
PREFIX: ${PREFIX}
EOF

# Also see: https://docs.blockscout.com/setup/requirements/client-settings#geth
#   Notes: http.api=shh is not supported anymore: https://github.com/ethereum/go-ethereum/releases/tag/v1.9.21
# --syncmode="full", because default "snap" mode and starting concurrently with ec-1 cause a stopped sync
# --bootnodes="" in the end to disable the default list, so we won't connect to predefined nodes
exec geth \
  --http \
  --http.addr=0.0.0.0 \
  --http.vhosts=* \
  --http.api=eth,web3,net,txpool,debug,admin,rpc \
  --http.corsdomain=* \
  --ws \
  --ws.addr=0.0.0.0 \
  --ws.api=eth,web3,net,txpool,debug,admin,rpc \
  --ws.rpcprefix=/ \
  --ws.origins=* \
  --authrpc.addr=0.0.0.0 \
  --authrpc.vhosts=* \
  --authrpc.jwtsecret="/etc/secrets/jwt-secret-${NODE_NUMBER}.hex" \
  --nodekey="/etc/secrets/p2p-key-${NODE_NUMBER}.hex" \
  --nat="extip:${IP}" \
  --netrestrict="${NETWORK}/${PREFIX}" \
  --syncmode="full" \
  --gcmode="archive" \
  --log.file="${LOG_FILE}" \
  --verbosity=5 \
  --log.format=terminal \
  --log.rotate \
  --log.compress \
  --bootnodes="" \
  $EXTRA_ARGS
