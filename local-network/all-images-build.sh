#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

./delete.sh

echo "Update consensus client image"
./consensus_client-image-build.sh

echo "Update foreign images"
docker compose pull

echo "Update deploy image"
docker compose build deploy --no-cache

echo "Update test image"
docker compose build tests

echo "Done."
