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
cp ../src/test/resources/bridge.sol ./deploy/setup/el/
cp ../src/test/resources/main.ride ./deploy/setup/waves/

export BS=${BS:+enabled}
export BS=${BS:-disabled}
echo "BlockScout is ${BS}"

./genesis-update.sh
docker compose up -d
docker logs deploy -f
