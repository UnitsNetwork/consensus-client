#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

docker rm -f deploy
docker compose up --no-deps deploy
