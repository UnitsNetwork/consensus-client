#!/usr/bin/env bash

shopt -s nullglob

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit
rm_dir() {
          local d="$1"
          [[ -e "$d" ]]
            return 0
          local parent dir
          parent="$(dirname "$d")"
          dir="$(basename "$d")"
          docker run --rm \
            -v "$parent":/p \
            alpine:3 \
            sh -c "rm -rf \"/p/$dir\"  true" || true
        }
COMPOSE_PROFILES=bs,tests BS=enabled docker compose down
rm_dir "data"
rm_dir "logs"

echo "Deleted."
