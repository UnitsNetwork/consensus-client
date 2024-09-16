import { RLP } from '@ethereumjs/rlp';
import * as wt from '@waves/waves-transactions';
import { Web3 } from 'web3';
import { WavesAccount } from './types';

const web3 = new Web3();
export const chainId = 68; // 'D' - DevNet

function calculateContractAddress(senderAddress: string, nonce: number): string {
  const rlpEncoded = RLP.encode([senderAddress, nonce]);
  const hash = web3.utils.keccak256(rlpEncoded);
  return '0x' + hash.slice(26);
}

function mkWavesAccount(seed: string, nonce: number): WavesAccount {
  const s = wt.libs.crypto.seedWithNonce(seed, nonce);
  return {
    privateKey: wt.libs.crypto.privateKey(s),
    publicKey: wt.libs.crypto.publicKey(s),
    address: wt.libs.crypto.address(s, chainId)
  }
}

export const elBridgeContractAddress = '0x1000000000000000000000000000000000000000';
export const chainContract = mkWavesAccount('devnet-1', 2);
export const wavesMiner1 = mkWavesAccount('devnet-1', 0);
export const wavesMiner2 = mkWavesAccount('devnet-2', 0);
