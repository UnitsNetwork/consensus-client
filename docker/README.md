# Launching the Units Network node
Units Network node consists of Waves blockchain node, Consensus Client extension, and an execution client (only [op-geth](https://github.com/ethereum-optimism/op-geth) is supported for now). This directory contains sample docker compose files for running the node.

## Prerequisites
* Install [Docker Compose](https://docs.docker.com/compose/install/).
* Generate JWT secret and execution client keys by running `./gen-keys.sh`. This script requires `openssl`.
* Optional: get waves node [state](https://docs.waves.tech/en/waves-node/options-for-getting-actual-blockchain/state-downloading-and-applying) and place it inside the `./data/waves` directory.
* Optional: get execution client state.
* To run op-geth on Linux, you need to manually create data & log directories and set appropriate permissions:
```
install -d -o 1000 -g 1000 data/op-geth logs/op-geth
```

## Configuring Waves Node
* Create `./secrets.env` file with the base58-encoded [seed and password](https://docs.waves.tech/en/waves-node/how-to-work-with-node-wallet):
  ```
  WAVES_WALLET_SEED=<base58-encoded seed>
  WAVES_WALLET_PASSWORD=<wallet password>
  ```
  This wallet seed will be used for mining both waves and ethereum blocks, so make sure it's the correct one.
* Specify the proper declared addresses in the environment file (`testnet.env` for testnet, etc.). Make sure these declared addresses have distinct ports, otherwise your node will be banned from the network!

## Launching

Running, stopping and updating in testnet:
```
docker compose --env-file=testnet.env up -d
docker compose --env-file=testnet.env down
docker compose --env-file=testnet.env pull
```
