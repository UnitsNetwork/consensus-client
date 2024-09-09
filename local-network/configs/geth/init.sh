#!/usr/bin/env sh
if [ ! -d /root/.ethereum/geth ] ; then
  geth init /tmp/genesis.json
else
  echo geth already initialized
fi
