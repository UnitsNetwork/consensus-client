#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

echo "Update consensus client image"
./consensus_client-image-build.sh

echo "Update foreign images"
BS=enabled docker compose pull --ignore-buildable

echo "Update deploy image"
docker compose build deploy --no-cache

echo "Update test image"
docker compose build test

echo "Done."
