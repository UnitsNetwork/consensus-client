#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

./stop.sh

if [[ "$(uname)" == "Linux" ]]; then
  sudo ./delete.sh
else
  ./delete.sh
fi

# We have to do this:
# 1. docker volume can't work with symlinks.
# 2. Just copying files makes easier code, that works both in host and docker.
mkdir -p ./deploy/setup/{el,waves}
cp ../src/test/resources/bridge.sol ./deploy/setup/el/
cp ../src/test/resources/main.ride ./deploy/setup/waves/

export COMPOSE_PROFILES="${COMPOSE_PROFILES:-}"
echo "Compose profiles are: ${COMPOSE_PROFILES}"

./genesis-update.sh
docker compose up -d
docker compose logs deploy tests -f
