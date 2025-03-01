#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

echo running $MAIN

# pip install --no-cache-dir --editable . # uncomment if you want to quickly update dependency
python -B $MAIN
