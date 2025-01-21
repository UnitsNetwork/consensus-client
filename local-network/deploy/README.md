To re-deploy from a container run the following command from the project's root directory (NOT FROM THIS DIRECTORY):
```bash
./deploy-run.sh
```

To run tests on the host machine, from this directory:
1. If you're on macOS with Apple Silicon: install `gcc`.
2. Create the virtual environment and install dependencies: `./dev-setup.sh`
3. Run test, e.g.: `./tests/transfer-multiple-c2e.py`

To generate `Bridge.java`, run:
```bash
web3j generate solidity \
  --abiFile=setup/el/compiled/Bridge.abi \
  --binFile=setup/el/compiled/Bridge.bin \
  --generateBoth \
  --outputDir=../../consensus-client-it/src/test/java/ \
  -p units.bridge \
  --primitiveTypes \
  -c BridgeContract
```

To generate `IssuedTokenBridge.java`, run:
```bash
web3j generate solidity \
  --abiFile=setup/el/compiled/IssuedTokenBridge.abi \
  --binFile=setup/el/compiled/IssuedTokenBridge.bin \
  --generateBoth \
  --outputDir=../../consensus-client-it/src/test/java/ \
  -p units.bridge \
  --primitiveTypes \
  -c IssuedTokenBridgeContract
```
