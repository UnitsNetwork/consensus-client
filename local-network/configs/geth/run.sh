#!/usr/bin/env sh

if [ ! -d /root/.ethereum/geth ] ; then
  geth init /tmp/genesis.json 2>&1 | tee /root/logs/init.log
fi

geth \
  --verbosity=4 \
  --http \
  --http.addr=0.0.0.0 \
  --http.vhosts=* \
  --http.api=eth,web3,txpool,net,debug,engine \
  --http.corsdomain=* \
  --ws \
  --ws.addr=0.0.0.0 \
  --ws.api=eth,web3,txpool,net,debug \
  --ws.rpcprefix=/ \
  --ws.origins=* \
  --authrpc.addr=0.0.0.0 \
  --authrpc.vhosts=* \
  --authrpc.jwtsecret=/etc/secrets/jwtsecret \
  --nodekey=/etc/secrets/p2p-key \
  --bootnodes=enode://b2ce9caff5e7472eafaf006904e2cb39cdd79801cda1328c510118cafdb0e9574526af6d05a89dae07a376606227c54c724cab1e88edf43190b7544976b275b8@ec-1:30303,enode://4e355eebfd77e5c2c0c20328c2bd5f3fde033c58e06e758c3e0a4ad88e8ced176f0d5eb32e214461b73e014591587f7c6567ee373e9c389b872a6d97d74a913c@ec-2:30303 \
2>&1 | tee /root/logs/geth.log # There can be logrotate or something like this
