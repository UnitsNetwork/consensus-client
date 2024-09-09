#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

for N in {1..5}; do
  p2p_file="p2p-key-${N}"
  jwt_file="jwtsecret-${N}"

  if [ ! -f "$p2p_file" ]; then
    openssl rand 32 | xxd -p -c 32 > "$p2p_file"
    echo "Created $p2p_file"
  else
    echo "$p2p_file already exists, skipping..."
  fi

  if [ ! -f "$jwt_file" ]; then
    openssl rand 32 | xxd -p -c 32 > "$jwt_file"
    echo "Created $jwt_file"
  else
    echo "$jwt_file already exists, skipping..."
  fi
done
