- [Glossary](#glossary)
- [How to bridge your token](#how-to-bridge-your-token)
    * [If a token issued on CL first](#if-a-token-issued-on-cl-first)
    * [If a token issued on EL first](#if-a-token-issued-on-el-first)
- [How to deploy and verify a token on EL](#how-to-deploy-and-verify-a-token-on-el)
    * [Prerequisites](#prerequisites)
    * [Example of command on TestNet](#example-of-command-on-testnet)
        + [Additional verification](#additional-verification)
- [How check a block finalization](#how-check-a-block-finalization)
- [How to check is token registered on EL](#how-to-check-is-token-registered-on-el)

All examples are written for TestNet.

# Glossary

- CL - Consensus Layer, Waves
- EL - Execution Layer, Ethereum

# How to bridge your token

## If a token issued on CL first

1. [Deploy](#how-to-deploy-and-verify-a-token-on-el) and verify a corresponding token on EL. It should:
    - Implement [IUnitsMintableERC20](eth/src/IUnitsMintableERC20.sol);
    - Have equal or greater number of decimals comparing to the CL token.
2. [Wait](#how-check-a-block-finalization) until the EL-block with the token issue finalized.
3. Register the token on CL using [ChainContract.registerAssets](waves/src/main.ride).
4. [Wait](#how-to-check-is-token-registered-on-el) until the token registration approved on EL.
5. Now you can make transfers between CL and EL.

https://explorer-testnet.unit0.dev/address/0x2EE5715961C45bd16EB5c2739397B8E871A46F9f?tab=read_write_proxy

### Example

1. CL token: [Alpha Token](https://wavesexplorer.com/assets/EGtpQnsp6FtRWKqoep6dnCCWDDDrfii1LsWBGYorMJKB?network=testnet).
2. EL token: [Alpha Token](https://explorer-testnet.unit0.dev/address/0x7250941ee49914e0575ce241178ce0c4a0765d17).
3. Register [transaction](https://wavesexplorer.com/transactions/7TYhuPE3EHcRzvph9N9jyGuDhjqBuefaYooF7UnGFvUc?network=testnet).

## If a token issued on EL first

1. Make sure the EL-token implements either [IUnitsMintableERC20](eth/src/IUnitsMintableERC20.sol) or at least _IERC20_;
2. Make [sure](#how-check-a-block-finalization) the EL-block with the token issue finalized.
3. Issue and register a corresponding token on CL using [ChainContract.issueAndRegister](waves/src/main.ride).

   It will issue a token.

4. [Wait](#how-to-check-is-token-registered-on-el) until the token registration approved on EL.
5. Now you can make transfers between CL and EL.

### Example

1. EL token: [Beta Token](https://explorer-testnet.unit0.dev/address/0xc023B7969e3046C589cc896820082B19E5F59E9F).
2. Register [transaction](https://wavesexplorer.com/transactions/CrFbRkzBykL1bha7gjF4giwQemZkiakQPLMZaDZqPHzH?network=testnet). 
3. CL token: [Beta Token](https://wavesexplorer.com/assets/B52i9pi8FMnpUsXj1cHKTkV9qDpG23WCAQAw7hKtghnQ?network=testnet).

# How to deploy and verify a token on EL

Let's deploy and verify our [token](eth/src/UnitsMintableERC20.sol) using [foundry](https://book.getfoundry.sh/index.html).

## Prerequisites

We need:

- Information about our token. For example:
    - Name: `Test Token`;
    - Symbol: `TTK`;
    - Decimals: `18`.
- Depending on network:
    - TestNet:
        - Standard bridge address: `0x2EE5715961C45bd16EB5c2739397B8E871A46F9f`;
        - RPC url: https://rpc-testnet.unit0.dev/
        - Explorer url: https://explorer-testnet.unit0.dev/
    - StageNet
        - Standard bridge address: `0x2EE5715961C45bd16EB5c2739397B8E871A46F9f`;
        - RPC url: https://rpc-stagenet.unit0.dev/
        - Explorer url: https://explorer-stagenet.unit0.dev/
- And your private key for L2 account that will deploy the contract.

## Example of command on TestNet

You can use [our](eth/src/UnitsMintableERC20.sol) implementation of [IUnitsMintableERC20](eth/src/IUnitsMintableERC20.sol).
Also see [TERC20.sol](eth/src/utils/TERC20.sol) as example of pre-minted tokens, if you want to issue on EL first.

```shell
forge create --force \
  --rpc-url https://rpc-testnet.unit0.dev/ \
  --broadcast \
  --verify \
  --verifier blockscout \
  --verifier-url https://explorer-testnet.unit0.dev/api/ \
  --private-key YOUR_PRIVATE_KEY \
  eth/src/UnitsMintableERC20.sol:UnitsMintableERC20 \
  --constructor-args 0x2EE5715961C45bd16EB5c2739397B8E871A46F9f "Test Token" "TTK" 18
```

Note **/api** in the end of the `--verifier-url`.
Please refer to the official [forge-create](https://book.getfoundry.sh/reference/forge/forge-create) documentation for more information.

You will see something like:
> [⠊] Compiling...
> [⠒] Compiling 8 files with Solc 0.8.29
> [⠢] Solc 0.8.29 finished in 74.47ms
> Compiler run successful!
> Deployer: <redacted>
> Deployed to: <redacted>
> Transaction hash: <redacted
> Starting contract verification...
> Waiting for blockscout to detect contract deployment...
> Start verifying contract `<redacted>` deployed on 88817
> Compiler version: 0.8.29
> Constructor args: <redacted>
>
> Submitting verification ...
> Submitted contract for verification:
> Response: `OK`
> ...

It can take some time for a verification, even it failed in the log. If it is not, try to [verify](#additional-verification) it again.

### Additional verification

Please refer to the official [forge-verify-contract](https://book.getfoundry.sh/reference/forge/forge-verify-contract) documentation for more information.

```shell
forge verify-contract \
  --rpc-url https://rpc-testnet.unit0.dev \
  <Address from "Deployed to"> \
  eth/src/StandardBridge.sol:StandardBridge \
  --verifier blockscout \
  --verifier-url https://explorer-testnet.unit0.dev/api/ \
  --constructor-args 0x2EE5715961C45bd16EB5c2739397B8E871A46F9f "Test Token" "TTK" 18
```

# How check a block finalization

You can use [ChainContract.isFinalized](waves/src/main.ride) for this.

Example of request:

```shell
curl 'https://nodes-testnet.wavesnodes.com/utils/script/evaluate/3Msx4Aq69zWUKy4d1wyKnQ4ofzEDAfv5Ngf' \
  -H 'Content-Type: application/json' \
  -d '{"expr": "isFinalized(\"a3b8fd2343e12a4c8ae6502baf5d4d01d9ccb459300a7ab94942573eb0df1ab3\")"}'
```

If request returns an information about the requested block, then it finalized.

# How to check is token registered on EL

1. Go to StandardBridge address page in explorer.
2. Open "Contract" tab.
3. Click on "Read/Write proxy" button.
4. Under "Contract information" click on "tokenRatios" and enter the address of the token and press "Read".

   If you see "(uint256) : 0" then the token is not registered yet. Other non-zero number means it is registered and ready for bridge transactions.

For example:

1. https://explorer-testnet.unit0.dev/address/0x2EE5715961C45bd16EB5c2739397B8E871A46F9f?tab=read_write_proxy
2. Address: `0x7250941ee49914e0575ce241178ce0c4a0765d17`
3. Registered, because "(uint256) : 1"
