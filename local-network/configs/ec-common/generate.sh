#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

for N in {1..4}; do
  p2p_file="p2p-key-${N}.hex"
  jwt_file="jwt-secret-${N}.hex"
  jwt_token_file="jwt-token-${N}.hex"

  # Generate p2p key without newline
  if [ ! -f "$p2p_file" ]; then
    openssl rand 32 | xxd -p -c 32 | tr -d '\n' > "$p2p_file"
    echo "Created $p2p_file"
  fi

  # Generate JWT secret without newline
  if [ ! -f "$jwt_file" ]; then
    openssl rand 32 | xxd -p -c 32 | tr -d '\n' > "$jwt_file"
    echo "Created $jwt_file"
  fi

  # Generate JWT token
  secret=$(cat "$jwt_file")
  ./jwt-token-generate.sh "$secret" > "$jwt_token_file"
  echo "Generated JWT token in $jwt_token_file"
done
