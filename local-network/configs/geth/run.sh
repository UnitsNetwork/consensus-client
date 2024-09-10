#!/usr/bin/env sh

if [ ! -d /root/.ethereum/geth ] ; then
  geth init /tmp/genesis.json 2>&1 | tee /root/logs/init.log
fi

geth \
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
  --authrpc.jwtsecret=/etc/secrets/jwtsecret \
  --nodekey=/etc/secrets/p2p-key \
  --nat=none \
  --bootnodes="${BESU_BOOTNODES}" \
  --log.file="/root/logs/geth.log" \
  --verbosity=4 \
  --log.format=terminal \
  --log.rotate \
  --log.compress
