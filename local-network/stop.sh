#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

COMPOSE_PROFILES=bs,tests docker compose stop
