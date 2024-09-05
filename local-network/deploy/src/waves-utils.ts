import * as waves from '@waves/node-api-js';
import { SignedTransaction } from '@waves/ts-types';
import { promises as fs } from 'fs';
import * as common from './common';
import * as logger from './logger';

export type LibraryWavesApi = ReturnType<typeof waves.create>;
export type ExtendedWavesApi = LibraryWavesApi & { base: string };
export type WavesSignedTransaction = SignedTransaction<any> & { id: string };

export interface EcBlockContractInfo {
  chainHeight: number,
  epochNumber: number
}

export async function waitForUp(wavesApi: ExtendedWavesApi): Promise<void> {
  while (true) {
    logger.debug(`Wait for ${wavesApi.base}`);
    try {
      const height = (await wavesApi.blocks.fetchHeight()).height;
      logger.info(`Current height: ${height}`);
      return;
    } catch (e) {
      logger.debug('API is not available');
    }
    await common.sleep(3000);
  }
}

export async function waitForTxn(wavesApi: LibraryWavesApi, id: string): Promise<any> {
  while (true) {
    logger.debug(`Wait for ${id}`);
    try {
      return await wavesApi.transactions.fetchInfo(id);
    } catch (e) {
      await common.sleep(1000);
    }
  }
}

export function sign(wavesApi: ExtendedWavesApi, unsignedTxJson: string): Promise<WavesSignedTransaction> {
  return wavesApi.tools.request({
    url: '/transactions/sign',
    base: wavesApi.base,
    options: {
      method: 'POST',
      body: unsignedTxJson,
      headers: [
        ['Content-Type', 'application/json'],
        ['X-API-Key', 'testapi']
      ]
    }
  });
}

export function createWalletAddress(wavesApi: ExtendedWavesApi): Promise<any> {
  return wavesApi.tools.request({
    url: '/addresses',
    base: wavesApi.base,
    options: {
      method: 'POST',
      headers: [
        ['Content-Type', 'application/json'],
        ['X-API-Key', 'testapi']
      ]
    }
  });
}

export function compile(wavesApi: ExtendedWavesApi, src: string, compact: boolean): Promise<any> {
  return wavesApi.tools.request({
    url: `/utils/script/compileCode?compact=${compact}`,
    base: wavesApi.base,
    options: {
      method: 'POST',
      body: src,
      headers: [
        ['Content-Type', 'application/json']
      ]
    }
  });
}

export function evaluate(wavesApi: ExtendedWavesApi, dAppAddress: string, request: object): Promise<any> {
  return wavesApi.tools.request({
    url: `/utils/script/evaluate/${dAppAddress}`,
    base: wavesApi.base,
    options: {
      method: 'POST',
      body: JSON.stringify(request),
      headers: [
        ['Content-Type', 'application/json']
      ]
    }
  });
}

export async function getTransactionJson(name: string): Promise<any> {
  const content = await fs.readFile(`./setup/waves/${name}.json`, { encoding: 'utf-8' });
  return JSON.parse(content);
}

export async function signAndBroadcastFromFile(wavesApi: ExtendedWavesApi, name: string, options: { wait: boolean } = { wait: false }): Promise<void> {
  const unsignedTxJson = await getTransactionJson(name);
  await signAndBroadcast(wavesApi, name, unsignedTxJson, options);
}

export async function signAndBroadcast(wavesApi: ExtendedWavesApi, name: string, unsignedTxJson: any, options: { wait?: boolean } = { wait: false }): Promise<void> {
  logger.info(`Sign ${name}`);

  const signedTxJson = await sign(wavesApi, JSON.stringify(unsignedTxJson));
  const id = signedTxJson.id;

  logger.info(`Broadcast ${id}`);
  try {
    const broadcastResult = await wavesApi.transactions.broadcast(signedTxJson);
    if (broadcastResult.id != id) {
      logger.error('Unexpected response: %o', broadcastResult);
      throw new Error('Failed to broadcast a transaction');
    }
  } catch (e) {
    logger.error(`Can't broadcast ${id}: %O`, e);
    throw e;
  }

  if (options.wait) await waitForTxn(wavesApi, id).then(x => logger.debug(`Sent %O result: %O`, unsignedTxJson, x));
}

function parseBlockMeta(response: object): EcBlockContractInfo {
  // @ts-ignore: Property 'value' does not exist on type 'object'.
  const rawMeta = response.result.value;
  return {
    chainHeight: rawMeta._1.value,
    epochNumber: rawMeta._2.value,
  };
}

export async function waitForEcBlock(wavesApi: LibraryWavesApi, chainContractAddress: string, blockHash: string): Promise<EcBlockContractInfo> {
  const getBlockData = async () => {
    try {
      return parseBlockMeta(await wavesApi.utils.fetchEvaluate(chainContractAddress, `blockMeta("${blockHash.slice(2)}")`));
    } catch (e) {
      return undefined;
    }
  };
  return await common.repeat(getBlockData, 2000);
}

export function prepareE2CWithdrawTxnJson(
  txnSenderAddress: string, chainContractAddress: string, blockHash: string,
  merkleTreeProofs: Buffer[], withdrawIndex: number, amount: bigint
): any {
  return {
    sender: txnSenderAddress,
    type: 16,
    fee: 500000,
    dApp: chainContractAddress,
    call: {
      function: "withdraw",
      args: [
        { type: 'string', value: blockHash.slice(2) },
        { type: 'list', value: merkleTreeProofs.map(x => ({ type: 'binary', value: x.toString('base64') })) },
        { type: 'integer', value: withdrawIndex },
        { type: 'integer', value: Number(amount) }
      ]
    },
    payment: []
  };
}

export async function chainContractCurrFinalizedBlock(wavesApi: LibraryWavesApi, chainContractAddress: string): Promise<EcBlockContractInfo> {
  // @ts-ignore: Property 'value' does not exist on type 'object'.
  return parseBlockMeta(await wavesApi.utils.fetchEvaluate(chainContractAddress, `blockMeta(getStringValue("finalizedBlock"))`));
}
