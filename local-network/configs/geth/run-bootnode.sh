#!/usr/bin/env sh

mkdir -p /root/logs

IP_RAW=$(ip -4 addr show dev eth0 | awk '/inet / {print $2}')
IP=$(echo "$IP_RAW" | cut -d/ -f1)
NETWORK=$(echo "$IP_RAW" | xargs ipcalc -n | awk -F= '{print $2}')
PREFIX=$(echo "$IP_RAW" | xargs ipcalc -p | awk -F= '{print $2}')

tee /root/logs/bootnode.log <<EOF
IP: $IP
NETWORK: $NETWORK
PREFIX: ${PREFIX}
EOF

bootnode \
  --nodekey /bootnode.key \
  --nat "extip:${IP}" \
  --addr ":30303" \
  --verbosity=5 \
  --netrestrict "${NETWORK}/${PREFIX}" \
  2>&1 | tee /root/logs/bootnode.log
