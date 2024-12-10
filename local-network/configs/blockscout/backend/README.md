# precompiled.json

* `bytecode` - the result of `solc --bin-runtime /path/to.sol`
* `source` - `jq -Rs '{source: .}' < /path/to.sol`
* `abi` - `solc --pretty-json --abi /path/to.sol | tail -n +4 | jq '{abi: . | tostring}'`
  - `+4` to skip heading [garbage](https://github.com/ethereum/solidity/issues/10275)
