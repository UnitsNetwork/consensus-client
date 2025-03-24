#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}/.." || exit

sbt -J-Xmx4G -J-Xss4m -Dfile.encoding=UTF-8 -Dsbt.supershell=false "docker"
