#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

./stop.sh

if [[ "$(uname)" == "Linux" ]]; then
  sudo ./delete.sh
else
  ./delete.sh
fi

export COMPOSE_PROFILES="${COMPOSE_PROFILES:-}"
echo "Compose profiles are: ${COMPOSE_PROFILES}"

docker compose up -d
docker compose logs deploy tests -f
