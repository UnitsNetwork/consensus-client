# Launching the Units Network node
Units Network node consists of Waves blockchain node, Consensus Client extension, and an execution client (only [op-geth](https://github.com/ethereum-optimism/op-geth) is supported for now). This directory contains sample docker compose files for running the node.

## Prerequisites
* Install [Docker Compose](https://docs.docker.com/compose/install/).
* Generate JWT secret and execution client keys by running `./gen-keys.sh`. This script requires `openssl`.
* Optional: get waves node [state](https://docs.waves.tech/en/waves-node/options-for-getting-actual-blockchain/state-downloading-and-applying) and place it inside the `./data/waves` directory.
* Optional: get execution client state.

## Configuring the nodes

Create `./local.env` file with the base58-encoded [seed and password](https://docs.waves.tech/en/waves-node/how-to-work-with-node-wallet) and declared addresses for both Waves and Unit Zero nodes:
```shell
WAVES_WALLET_SEED=<base58-encoded seed>
WAVES_WALLET_PASSWORD=<wallet password>
WAVES_DECLARED_ADDRESS=1.2.3.4:6868
UNITS_DECLARED_ADDRESS=1.2.3.4:6865
```
This wallet seed will be used for mining both waves and ethereum blocks, so make sure it's the correct one. Also make sure these declared addresses have distinct ports, otherwise your node will be banned from the network!

You can also override Consensus Client image tag by adding `UNITS_IMAGE_TAG` variable to `./local.env`:
```shell
UNITS_IMAGE_TAG=snapshot
```

## Launching

Running, stopping and updating in testnet:
```shell
docker compose --env-file=testnet.env up -d
docker compose --env-file=testnet.env down
docker compose --env-file=testnet.env pull
```
