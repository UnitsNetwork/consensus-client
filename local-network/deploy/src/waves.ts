import * as waves from '@waves/node-api-js';
import { DataTransactionEntry } from '@waves/ts-types';
import { TLong } from '@waves/node-api-js/cjs/interface';
import * as wt from '@waves/waves-transactions';
import { promises as fs } from 'fs';
import * as common from './common';
import * as s from './common-settings';
import * as logger from './logger';
import * as wavesTxs from './waves-txs';
import * as wavesUtils from './waves-utils';
import { ExtendedWavesApi } from './waves-utils';
import { wavesApi1, wavesApi2, ec1Rpc } from './nodes';

const wavesApis = [wavesApi1, wavesApi2];
const chainContractAddress = s.chainContract.address;

export interface SetupResult {
  wavesApi1: ExtendedWavesApi;
  wavesApi2: ExtendedWavesApi;
  chainContractAddress: string;
  tokenId: string;
  utils: typeof wavesUtils;
}

export async function setup(force: boolean): Promise<SetupResult> {
  logger.info('Set up CL');

  const deployChainContract = async (): Promise<boolean> => {
    const scriptInfo = await wavesApi1.addresses.fetchScriptInfo(s.chainContract.address);
    if (scriptInfo.script) {
      logger.info(`${s.chainContract.address} already has a script.`);
      return false;
    } else {
      logger.info('Compile CL chain contract');
      const chainContractCode = await fs.readFile('./setup/waves/main.ride', 'utf-8');
      // const compiledChainContract = await wavesApi.utils.fetchCompileCode(chainContractCode); // don't have a compaction
      const compiledChainContract = await wavesUtils.compile(wavesApi1, chainContractCode, true);

      logger.info('Deploy CL chain contract');
      const tx = wavesTxs.mkCcDeploy(compiledChainContract.script);
      await wavesApi1.transactions.broadcast(tx)
      await wavesUtils.waitForTxn(wavesApi1, tx.id);
      return true;
    }
  };

  const setupChainContract = async (): Promise<void> => {
    const elGenesisBlock = await ec1Rpc.eth.getBlock(0);
    if (!elGenesisBlock.hash) throw new Error("No genesis block")
    logger.info(`EL genesis block hash: ${elGenesisBlock.hash}`);

    const tx = wavesTxs.ccSetup(elGenesisBlock.hash);
    const isContractSetupResponse = await wavesApi1.utils.fetchEvaluate(tx.dApp, 'isContractSetup()');
    // @ts-ignore: Property 'value' does not exist on type 'object'.
    if (isContractSetupResponse.result.value) {
      logger.info('The contract is already set up.');
    } else {
      await wavesApi1.transactions.broadcast(tx);
      await wavesUtils.waitForTxn(wavesApi1, tx.id);
    }
  };

  const join = async (tx: any): Promise<void> => {
    const minerAddress = wt.libs.crypto.address({ publicKey: tx.senderPublicKey }, s.chainId);
    const isMinerJoinedResponse = await wavesApi1.utils.fetchEvaluate(chainContractAddress, `isDefined(getString("miner_${minerAddress}_RewardAddress"))`);
    // @ts-ignore: Property 'value' does not exist on type 'object'.
    if (isMinerJoinedResponse.result.value) {
      logger.info(`The ${minerAddress} miner has already joined`);
    } else {
      await wavesApi1.transactions.broadcast(tx);
    }
  };

  logger.info('Wait Waves 1 node');
  await wavesUtils.waitForUp(wavesApi1);

  logger.info('Wait Waves node has at least one peer'); // Otherwise we can have problems with rollbacks
  let connectedPeers = 0;
  do {
    await common.sleep(2000);
    connectedPeers = (await wavesApi1.peers.fetchConnected()).peers.length;
  } while (connectedPeers < 1);

  logger.info(`Waves nodes peers: ${connectedPeers}`);

  logger.info('Deploy chain contract');
  const isNew = await deployChainContract();
  const waitTime = 3000;// To eliminate micro fork issue
  if (isNew) await common.sleep(waitTime);

  if (isNew || force) {
    logger.info('Setup chain contract');
    await setupChainContract();
    if (isNew) await common.sleep(waitTime);

    logger.info('Join miner 1');
    await join(wavesTxs.ccMinerJoin1);

    logger.info('Join miner 2');
    await join(wavesTxs.ccMinerJoin2);
  }

  logger.info('Get token id on CL');
  const tokenIdResponse = await wavesApi1.addresses.fetchDataKey(chainContractAddress, 'tokenId');
  if (!tokenIdResponse.value || tokenIdResponse.type != 'string') throw new Error(`Unexpected value of "tokenId" contract key in response: ${tokenIdResponse}`);

  const tokenId = tokenIdResponse.value;
  logger.info(`Asset id ${tokenId} from %O`, tokenIdResponse);

  logger.info('CL was set up');
  return {
    wavesApi1,
    wavesApi2,
    chainContractAddress: chainContractAddress,
    tokenId: tokenId,
    utils: wavesUtils
  };
}
