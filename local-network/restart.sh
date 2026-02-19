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

export COMPOSE_PROFILES="${COMPOSE_PROFILES:-}"
echo "Compose profiles are: ${COMPOSE_PROFILES}"

docker compose up -d

# HACK: This command hangs forever in a new Docker version: docker compose logs deploy tests -f
mapfile -t ids < <(docker compose ps -aq deploy tests)

if ((${#ids[@]} > 0)); then
  pids=()

  # Follow logs in background
  for id in "${ids[@]}"; do
    docker logs -f "$id" &
    pids+=("$!")
  done

  # Wait for containers to exit
  docker wait "${ids[@]}" >/dev/null

  # Stop log followers
  kill "${pids[@]}" 2>/dev/null || true
  wait "${pids[@]}" 2>/dev/null || true
fi
