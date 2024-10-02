#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

COMPOSE_PROFILES=tests BS=enabled docker compose stop
