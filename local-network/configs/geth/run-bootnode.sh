#!/usr/bin/env sh

mkdir -p /root/logs

bootnode \
  --nodekey /bootnode.key \
  --nat "extip:172.18.0.2" \
  --addr ":30303" \
  --verbosity=5 \
  --netrestrict 172.18.0.0/16 \
  > /root/logs/bootnode.log 2>&1
