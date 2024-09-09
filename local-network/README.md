# How to update from repository

1. Make sure all related containers stopped.
2. `git pull`
3. Now you can start containers.

# How to run

## Pre-requirements

You have to build consensus client. Run [./consensus_client-image-build.sh](./consensus_client-image-build.sh)

## How to start

- Run `./all-images-build.sh` after pulling the repository, so docker compose will pull new external images and rebuild
  the `deploy` image.
- If you need to run Blockscout, run `BS=1 ./restart.sh`. Otherwise, just [./restart.sh](./restart.sh).
- If deploy failed, and you want to retry, run `./deploy-run.sh`.

## How to stop

* Just stop: `docker compose down`
* Stop and delete the state: [./delete.sh](./delete.sh)

## How to test

See [./deploy](./deploy/).

# Keys

* Node HTTP API Key: `testapi`
* CL accounts (see [genesis-template.conf](./configs/wavesnode/common/genesis-template.conf) for more information):
    * Node wallet seed:
        * wavesnode-1: `devnet-1`
        * wavesnode-2: `devnet-2`
    * Chain contract: `3FdaanzgX4roVgHevhq8L8q42E7EZL9XTQr`
    * Staking contract: `3FSgXpgbT6m1speWgVx3cVxAZKmdr4barHU`
* EL mining reward accounts:
    * Reward account for **Miner 1** (`wavesnode-1`, `besu-1`):
        * Address: `0x7dBcf9c6C3583b76669100f9BE3CaF6d722bc9f9`
        * Private key: `16962bb06858ec2e4f252b01391196a5e3699329ff0ce1cc185c213a3844b1cf`
    * Reward account for **Miner 2** (`wavesnode-2`, `besu-2`):
        * Address: `0xcF0b9E13FDd593f4Ca26D36aFCaA44dd3FDCCbeD`
        * Private key: `ab49fee4fc326ecbc7abc7f2e5870bf1f86076eb0979c524e20c843f2a73f647`
    * To see all information, run `npx tsx common-settings-show.ts` from [./deploy](./deploy/) directory.
* Ethereum addresses and private keys for `besu` nodes in [config](configs/ec-common/genesis.json):
    * `fe3b557e8fb62b89f4916b721be55ceb828dbd73`: `8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63`
    * `f17f52151EbEF6C7334FAD080c5704D77216b732`: `ae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f`

Note: `besu` accounts form enode addresses and only needed to bootstrap a peers database. Other useful part: they have
a non-empty balance. So you can use them to issue transactions on EL.

# APIs

* BlockScout (if enabled): http://127.0.0.1:3000/
* Node HTTP APIs:
    * wavesnode-1: http://127.0.0.1:16869/
    * wavesnode-2: http://127.0.0.1:26869/

# How to set up Metamask

Settings:

- Network name: Waves Unit0 dev
- New RPC URL: http://127.0.0.1:18545
- Chain ID: 1337
- Currency symbol: Unit0 (or any other, name it!)

# How to set up Remix

For example, we will deploy a `HelloWorld`:

1. Make sure, Metamask is working.
2. Open https://remix.ethereum.org
3. Compile `HelloWorld` contract:
    1. Choose "Workspaces" on the sidebar ("Two files" icon).
    2. Press "Start coding" button, `HelloWorld.sol` will appear in the tree.
    3. Choose `HelloWorld.sol`, open a context menu (right mouse button) and choose "Compile".
4. Deploy the contract:
    1. Choose "Deploy & Run Transactions" on the sidebar ("Ethereum" icon).
    2. Set up the deployment settings:
        * *Environment*: "Injected Provider - Metamask".
        * *Account*: Choose a preferred account.
    3. Click on "Deploy" button.

# Troubleshooting

1. Make sure you have no pending transactions in Metamask before running containers. To delete:
    1. Go to Metamask settings.
    2. "Advanced" tab.
    3. Press "Clear activity tab data" button.
2. If you want to deploy a contract via Explorer ("Contracts" tab), make sure you've chosen not a nightly version and it
   matches [bridge.sol](./deploy/setup/el/bridge.sol).
 
# Useful links

## Besu configuration

See [Besu configuration](configs/besu/).

## How to change Waves initial miners or time between blocks

1. Update [genesis-template.conf](./configs/wavesnode/common/genesis-template.conf).
2. [genesis.conf](./configs/wavesnode/common/genesis.conf) will be updated in [restart.sh](./restart.sh).
