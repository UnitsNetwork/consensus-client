#!/usr/bin/env bash

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${DIR}" || exit

grep -E 'Waves .+ Blockchain Id' wavesnode-1/waves.log > diag/version-1.log
grep -E 'Waves .+ Blockchain Id' wavesnode-2/waves.log > diag/version-2.log

grep -E 'Invoking' wavesnode-1/waves.log > diag/mining-1.log
grep -E 'Invoking' wavesnode-2/waves.log > diag/mining-2.log

grep -E ' (E|W) ' wavesnode-1/waves.log > diag/errors-1.log
grep -E ' (E|W) ' wavesnode-2/waves.log > diag/errors-2.log
