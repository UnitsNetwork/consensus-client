#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}/.." || exit

java_version=$(java -version 2>&1 | awk -F[\"_] '/version/ {print $2}')
if [[ $java_version != 11* ]]; then
  echo "Error: required Java 11, got: $java_version"
  exit 1
fi

sbt -J-Xmx4G -J-Xss4m -Dfile.encoding=UTF-8 -Dsbt.supershell=false buildTarballsForDocker
docker build -t unitsnetwork/consensus-client:main docker
