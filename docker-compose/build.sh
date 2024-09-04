#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

docker compose pull
docker compose build deploy
echo "Done."
