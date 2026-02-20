#!/usr/bin/env bash
set -eu

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

./stop.sh

if [[ "$(uname)" == "Linux" ]]; then
  sudo ./delete.sh
else
  ./delete.sh
fi

COMPOSE_PROFILES="${COMPOSE_PROFILES:-}"
echo "Compose profiles are: ${COMPOSE_PROFILES}"

docker compose --profile nodes `[[ "${COMPOSE_PROFILES}" == *bs* ]] && echo --profile bs` up -d
docker compose --profile nodes up deploy `[[ "${COMPOSE_PROFILES}" == *tests* ]] && echo tests`
