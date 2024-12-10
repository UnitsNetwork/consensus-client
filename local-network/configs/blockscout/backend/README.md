# precompiled.json

* `bytecode` - the result of `solc --bin-runtime ...`
* `source` - `jq -Rs '{content: .}' < /path/to.sol`
* `abi` - the result of `solc --pretty-json --abi /path/to.sol | tail -n +4 | jq '. | tostring'`
  - `+4` to skip heading [garbage](https://github.com/ethereum/solidity/issues/10275)
