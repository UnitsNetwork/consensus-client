import * as wt from '@waves/waves-transactions';
import * as s from './common-settings';

// cc - Chain contract
// 1. Deploy chain contract script
export function mkCcDeploy(script: string) {
  return wt.setScript(
    {
      chainId: s.chainId,
      fee: 2_900_000,
      script: script
    },
    { privateKey: s.chainContract.privateKey }
  )
}

// 2. Setup chain contract
export function ccSetup(elGenesisBlockHashHex: string) {
  return wt.invokeScript(
    {
      chainId: s.chainId,
      fee: 100_500_000,
      dApp: s.chainContract.address,
      call: {
        function: "setup",
        args: [
          {
            // genesisBlockHashHex
            type: "string",
            value: elGenesisBlockHashHex.substring(2)
          },
          {
            // minerRewardInGwei
            type: "integer",
            // 2_000_000_000 Gwei = 2_000_000_000*10^9 Wei = 2*10^18 Wei = 2 UNIT0 for epoch, 
            // see bridge.sol for conversion details
            value: 2_000_000_000
          }
        ]
      }
    },
    { privateKey: s.chainContract.privateKey }
  )
}

// 3 & 4. Join EL miners on CL
export const ccMinerJoin1 = wt.invokeScript(
  {
    chainId: s.chainId,
    fee: 500_000,
    dApp: s.chainContract.address,
    call: {
      function: "join",
      args: [
        {
          // Reward address
          type: "binary",
          // 0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f9
          value: "base64:fbz5xsNYO3ZmkQD5vjyvbXIryfk="
        }
      ]
    }
  },
  { privateKey: s.wavesMiner1.privateKey }
);

export const ccMinerJoin2 = wt.invokeScript(
  {
    chainId: s.chainId,
    fee: 500_000,
    dApp: s.chainContract.address,
    call: {
      function: "join",
      args: [
        {
          // Reward address
          type: "binary",
          // 0xcf0b9e13fdd593f4ca26d36afcaa44dd3fdccbed
          value: "base64:zwueE/3Vk/TKJtNq/KpE3T/cy+0="
        }
      ]
    }
  },
  { privateKey: s.wavesMiner2.privateKey }
);

// Setup is done

// CL -> EL withdraw
export function mkC2ETransfer(senderPrivateKey: string, destElAddressHex: string, amount: bigint, tokenIdB58: string, timestamp?: number) {
  return wt.invokeScript(
    {
      chainId: s.chainId,
      fee: 500_000,
      dApp: s.chainContract.address,
      call: {
        function: "transfer",
        args: [
          { type: 'string', value: destElAddressHex.slice(2) },
        ]
      },
      payment: [
        {
          amount: Number(amount),
          assetId: tokenIdB58
        }
      ],
      timestamp: timestamp || Date.now()
    },
    { privateKey: senderPrivateKey }
  )
}

// EL -> CL transfer
export function mkE2CTransfer(senderPrivateKey: string, blockHash: string, merkleTreeProofs: Buffer[], withdrawIndex: number, amount: bigint) {
  return wt.invokeScript(
    {
      chainId: s.chainId,
      fee: 500_000,
      dApp: s.chainContract.address,
      call: {
        function: "withdraw",
        args: [
          { type: 'string', value: blockHash.slice(2) },
          { type: 'list', value: merkleTreeProofs.map(x => ({ type: 'binary', value: x.toString('base64') })) },
          { type: 'integer', value: withdrawIndex },
          { type: 'integer', value: Number(amount) }
        ]
      }
    },
    { privateKey: senderPrivateKey }
  )
}

