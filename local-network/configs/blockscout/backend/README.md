# precompiled.json

One script: [./precompile.sh](./precompile.sh).

By parts:
* `bytecode` - the result of `solc --bin-runtime /path/to.sol`;
* `source` - `jq -Rs '{source: .}' < /path/to.sol`;
* `abi` - `solc --pretty-json --abi /path/to.sol`.
