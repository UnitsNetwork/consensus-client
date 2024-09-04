# Launching the Units Network node
Units Network node consists of Waves blockchain node, Consensus Client extension, and an execution client (either [besu](https://besu.hyperledger.org) or [geth](https://geth.ethereum.org)). This directory contains sample docker compose files for running the node.

## Prerequisites
* Install [Docker Compose](https://docs.docker.com/compose/install/).
* Generate JWT secret and execution client keys by running `./gen-keys.sh`. This script requires `openssl` and `xxd`.
* Optional: get waves node [state](https://docs.waves.tech/en/waves-node/options-for-getting-actual-blockchain/state-downloading-and-applying) in the `./data/waves` directory.
* Optional: get execution client state.

## Configuring Waves Node
* Create `./secrets.yml` file with the base58-encoded [seed and password](https://docs.waves.tech/en/waves-node/how-to-work-with-node-wallet):
  ```
  WAVES_WALLET_SEED=<base58-encoded seed>
  WAVES_WALLET_PASSWORD=<wallet password>
  ```
  This wallet seed will be used for mining both waves and ethereum blocks, so make sure it's the correct one.
* Specify the proper declared addresses in the environment file (`testnet.env` for testnet, etc.). Make sure these declared addresses have distinct ports, otherwise your node will be banned from the network!

## Launching
Running, stopping and updating with besu in testnet:
```
docker compose --env-file=testnet.env up -d
docker compose --env-file=testnet.env down
docker compose --env-file=testnet.env pull
```
Running, stopping and updating with geth in testnet:
```
docker compose -f docker-compose-geth.yml --env-file=testnet.env up -d
docker compose -f docker-compose-geth.yml --env-file=testnet.env down
docker compose -f docker-compose-geth.yml --env-file=testnet.env pull
```
