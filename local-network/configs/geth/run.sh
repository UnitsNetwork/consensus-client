#!/usr/bin/env sh

if [ ! -d /root/.ethereum/geth ] ; then
  geth init /tmp/genesis.json 2>&1 | tee /root/logs/init.log
fi

# --syncmode full, because default "snap" mode and starting concurrently with ec-1 cause a stopped sync
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
  --nat="extip:172.18.0.4" \
  --netrestrict="172.18.0.0/16" \
  --bootnodes="enode://7804485fc3eed8f7f5ccd61f44d6925bc3c4adb3dd9bcd417da7f74ff4e518c0367697ca74b375d134d5a73bd731076d8498391b20bd862f6b763d4299f487e2@172.18.0.2:0?discport=30303" \
  --syncmode full \
  --log.file="/root/logs/geth.log" \
  --verbosity=5 \
  --log.format=terminal \
  --log.rotate \
  --log.compress
