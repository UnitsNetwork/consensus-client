import { ethAddress2waves } from '@waves/node-api-js';
import { blake2b } from '@waves/ts-lib-crypto';
import { MerkleTree } from 'merkletreejs';
import { setup } from '../setup';
import * as common from '../src/common';
import * as logger from '../src/logger';
import * as wavesTxs from '../src/waves-txs';
import * as s from '../src/common-settings';
import { WavesAccount } from '../src/types';

const { waves, el } = await setup(false);

logger.info("Running EL->CL withdrawal test");

const senderAccountPrivateKey = '0xae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f';
const senderAccount = el.ec1Rpc.eth.accounts.privateKeyToAccount(senderAccountPrivateKey);
logger.info(`Sender account address: ${senderAccount.address}`);

const recipient1 = s.wavesMiner1;
const recipient2 = s.wavesMiner2;

const withdrawIndex = 4;
const withdrawArgs = [
  [recipient2, '0.05'],
  [recipient2, '0.01'],
  [recipient2, '0.01'],
  [recipient2, '0.05'],
  [recipient1, '1.004'],
  [recipient2, '0.001'],
  [recipient1, '0.002'],
  [recipient1, '0.003'],
  [recipient1, '0.004'],
];

const nonce = Number(await el.ec1Rpc.eth.getTransactionCount(senderAccount.address, 'pending'));
logger.info(`Call Bridge.send ${withdrawArgs.length} times`);
logger.info(`Current nonce: ${nonce}`);

const withdrawals = withdrawArgs.map(async (x, i) => el.sendFromBridge(`${i}`, el.ec1Rpc, senderAccount, x[0].address, el.ec1Rpc.utils.toWei(x[1], 'ether'), { nonce: el.ec1Rpc.utils.numberToHex(nonce + i) }));
const withdrawalHashes = await Promise.all(withdrawals);
logger.info(`Sent all transactions: ${withdrawalHashes.join(', ')}`);

const elRequestReceipts = await Promise.all(withdrawalHashes.map(t => el.utils.waitForTransaction(el.ec1Rpc, t)));
logger.info('Got receipts for all transactions');

const uniqBlockHashes = new Set(elRequestReceipts.map(t => t.blockHash));
const blockHashes = Array.from(uniqBlockHashes);
if (blockHashes.length > 1) throw new Error(`Block hashes more than 1: ${JSON.stringify(blockHashes)}, a rare case. Retry the test.`);

const blockHash = blockHashes[0];
logger.info(`All transactions in ${blockHash}`);

const rawLogsInBlock = await el.ec1Rpc.eth.getPastLogs({ blockHash: blockHash });
if (typeof rawLogsInBlock == "string") throw new Error(`Unexpected getPastLogs result. Expected an object, got: ${rawLogsInBlock}`);
logger.info(`Logs in block: %O`, rawLogsInBlock);
if (rawLogsInBlock.length == 0) throw new Error(`Can't find logs in ${blockHash}`);
const logsInBlock = rawLogsInBlock as Exclude<typeof rawLogsInBlock[number], string>[];

logger.info(`Waiting EL block ${blockHash} confirmation on CL`);
const withdrawBlockMeta = await waves.utils.waitForBlock(waves.wavesApi1, waves.chainContractAddress, blockHash);
logger.info(`Withdraw block meta: %O`, withdrawBlockMeta);

let rawData: string[] = [];
let transferData: { wavesAccount: WavesAccount, amount: bigint }[] = [];
for (let l of logsInBlock) {
  if (l.data && l.topics && l.address) { // TODO check this values?
    const decoded = el.ec1Rpc.eth.abi.decodeLog(el.SentEventAbi.inputs, l.data, l.topics);

    const wavesRecipientPkHash = decoded[0] as string;
    const wavesAddress = ethAddress2waves(wavesRecipientPkHash, s.chainId);

    const amount = decoded[1] as bigint;

    logger.info(`Found transfer of ${amount} to ${wavesAddress}`);
    rawData.push(l.data);
    transferData.push({
      wavesAccount: (wavesAddress == recipient1.address) ? recipient1 : recipient2,
      amount: amount
    });
  }
}

const emptyLeafArr = new Uint8Array([0]);
const emptyLeaf = Buffer.from(emptyLeafArr.buffer, emptyLeafArr.byteOffset, emptyLeafArr.byteLength);
const emptyHashedLeaf = defBlake2b(emptyLeaf);

let leaves = rawData.map(x => defBlake2b(Buffer.from(x.slice(2), 'hex')));
for (let i = 1024 - leaves.length; i > 0; i--) {
  leaves.push(emptyHashedLeaf);
}

function defBlake2b(buffer: Buffer): Buffer {
  const arr = new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  const hashedArr = blake2b(arr);
  return Buffer.from(hashedArr.buffer, hashedArr.byteOffset, hashedArr.byteLength);
}

async function assetBalance(addr: string): Promise<bigint> {
  const r = await waves.wavesApi1.assets.fetchBalanceAddressAssetId(addr, waves.tokenId);
  return BigInt(r.balance);
}

const withdraw = transferData[withdrawIndex];

const merkleTree = new MerkleTree(leaves, defBlake2b);
let proofs = merkleTree.getProof(leaves[withdrawIndex], withdrawIndex);

const recipient1BalanceBefore = await assetBalance(recipient1.address);
const recipient2BalanceBefore = await assetBalance(recipient2.address);

logger.info(`Wait until EL block #${withdrawBlockMeta.chainHeight} becomes finalized`);
await common.repeat(async () => {
  // NOTE: values sometimes are cached if we ask this a dockerized service
  const currFinalizedBlock = await waves.utils.chainContractCurrFinalizedBlock(waves.wavesApi1, waves.chainContractAddress);
  logger.debug(`Current finalized height: ${currFinalizedBlock.chainHeight}`);
  return currFinalizedBlock.chainHeight < withdrawBlockMeta.chainHeight ? null : true;
}, 2000);

let proofsArg = proofs.map(x => {
  return {
    type: "binary",
    value: x.data.toString('base64')
  };
});
const withdrawCall = {
  function: "withdraw",
  args: [
    {
      type: "string",
      value: blockHash.slice(2)
    },
    {
      type: "list",
      value: proofsArg
    },
    {
      type: "integer",
      value: withdrawIndex
    },
    {
      type: "integer",
      value: Number(withdraw.amount)
    },
  ]
};
logger.info("Query: %o", withdrawCall);

let draftWithdrawResult = await waves.utils.evaluate(waves.wavesApi1, waves.chainContractAddress, {
  call: withdrawCall,
  sender: withdraw.wavesAccount.address,
});
logger.info(`Draft withdraw result: ${JSON.stringify(draftWithdrawResult)}`);

// const unsignedWithdrawTxn = waves.utils.prepareE2CWithdrawTxnJson(...);
const withdrawTxn = wavesTxs.mkE2CTransfer(withdraw.wavesAccount.privateKey, blockHash, proofs.map(x => x.data), withdrawIndex, withdraw.amount);
logger.debug('Withdraw txn: %O', withdrawTxn);
waves.wavesApi1.transactions.broadcast(withdrawTxn);

await waves.utils.waitForTxn(waves.wavesApi1, withdrawTxn.id);

const recipient1BalanceAfter = await assetBalance(recipient1.address);
const recipient2BalanceAfter = await assetBalance(recipient2.address);

const recipient1Transferred = recipient1BalanceAfter - recipient1BalanceBefore;
const recipient2Transferred = recipient2BalanceAfter - recipient2BalanceBefore;

logger.info(`Transferred to ${recipient1.address}: ${recipient1Transferred}`);
logger.info(`Transferred to ${recipient2.address}: ${recipient2Transferred}`);

const expectedRecipient1Transfer = withdraw.wavesAccount.address == recipient1.address ? BigInt(withdraw.amount) : 0n;
const expectedRecipient2Transfer = withdraw.wavesAccount.address == recipient2.address ? BigInt(withdraw.amount) : 0n;

if (expectedRecipient1Transfer - recipient1Transferred != 0n) logger.error(`Unexpected transfer amount to ${recipient1.address}: ${recipient1Transferred}, expected: ${expectedRecipient1Transfer}`);
if (expectedRecipient2Transfer - recipient2Transferred != 0n) logger.error(`Unexpected transfer amount to ${recipient2.address}: ${recipient2Transferred} expected: ${expectedRecipient2Transfer}`);

// Repeated transfer:
// await waves.utils.signAndBroadcast(
//   withdraw.wavesAddress == recipient1 ? waves.wavesApi1 : waves.wavesApi2,
//   `withdraw ${withdrawIndex} again`,
//   unsignedWithdrawTxn,
//   { wait: true }
// );

logger.info('Done');
