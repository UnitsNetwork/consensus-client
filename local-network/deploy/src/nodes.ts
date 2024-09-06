import * as waves from '@waves/node-api-js';
import Web3 from 'web3';
import * as common from './common';
import * as logger from './logger';
import { ExtendedWavesApi } from './waves-utils';

const insideDocker = await common.isInsideDocker();

function getWavesApiUrl(insideDocker: boolean, i: number): string {
  return insideDocker ? `http://wavesnode-${i}:6869` : `http://127.0.0.1:${i}6869`;
}

function getEcApiUrl(insideDocker: boolean, i: number): string {
  return insideDocker ? `http://besu-${i}:8545` : `http://127.0.0.1:${i}8545`;
}

function mkWeb3(ecRpcUrl: string): Web3 {
  const ecRpc = new Web3(ecRpcUrl);
  ecRpc.eth.transactionConfirmationBlocks = 1;
  ecRpc.eth.transactionBlockTimeout = 10;
  ecRpc.eth.transactionPollingTimeout = 120000;
  ecRpc.eth.transactionPollingInterval = 3000;
  return ecRpc;
}

const ec1RpcUrl = getEcApiUrl(insideDocker, 1);
const ec2RpcUrl = getEcApiUrl(insideDocker, 2);
logger.info(`EL clients RPC: ${ec1RpcUrl}, ${ec2RpcUrl}`);

const ec1Rpc = mkWeb3(ec1RpcUrl);
const ec2Rpc = mkWeb3(ec2RpcUrl);

const wavesApi1Url = getWavesApiUrl(insideDocker, 1);
const wavesApi2Url = getWavesApiUrl(insideDocker, 2);
logger.info(`Waves Node HTTP API: ${wavesApi1Url}, ${wavesApi2Url}`);

const wavesApi1: ExtendedWavesApi = { ...waves.create(wavesApi1Url), base: wavesApi1Url };
const wavesApi2: ExtendedWavesApi = { ...waves.create(wavesApi2Url), base: wavesApi2Url };

export {
  wavesApi1,
  wavesApi2,
  ec1Rpc,
  ec2Rpc
};

