#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

# pip install --no-cache-dir --editable . # uncomment if you want to quickly update dependency
echo running $MAIN
python -B $MAIN
