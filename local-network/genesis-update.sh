#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

CACHE_DIR=".cache"
JAR_FILE="waves.jar"
URL="https://github.com/wavesplatform/Waves/releases/download/v1.5.6/waves-all-1.5.6.jar"

mkdir -p "$CACHE_DIR"

if [ ! -f "$CACHE_DIR/$JAR_FILE" ]; then
    echo "Downloading $JAR_FILE..."
    curl -L -o "$CACHE_DIR/$JAR_FILE" "$URL"
    echo "Download completed."
else
    echo "$JAR_FILE already exists in $CACHE_DIR."
fi

java -cp "${DIR}/.cache/waves.jar" com.wavesplatform.GenesisBlockGenerator \
  "${DIR}/configs/wavesnode/common/genesis-template.conf" \
  "${DIR}/configs/wavesnode/common/genesis.conf" > /dev/null

echo "Genesis config updated"
