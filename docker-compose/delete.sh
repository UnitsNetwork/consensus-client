#!/usr/bin/env bash

shopt -s nullglob

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

BS=enabled docker compose down
rm -rf data/*/* || true
rm -rf logs/*/* || true

echo "Deleted."
