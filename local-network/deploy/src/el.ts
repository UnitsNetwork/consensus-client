import { base58Decode } from '@waves/ts-lib-crypto';
import * as fs from 'fs';
import { AbiEventFragment, Contract, ContractAbi, Web3 } from 'web3';
import * as common from './common';
import * as commonSettings from './common-settings';
import * as ecUtils from './el-utils';
import * as logger from './logger';
import { ec1Rpc, ec2Rpc } from './nodes';

interface Web3Account {
  address: string;
  privateKey: string;
}

/**
   * @param wavesRecipientAddress Base58 string
   * @param amount Use web3.utils.toWei
   * @returns Transaction hash
   */
async function sendFromBridge(
  contract: Contract<ContractAbi>,
  label: string,
  ecRpc: Web3,
  senderAccount: Web3Account,
  wavesRecipientAddress: string,
  amount: string,
  opts?: { [key: string]: any }
): Promise<string> {
  const senderAddress = senderAccount.address;
  const wavesRecipientPkHashBytes = base58Decode(wavesRecipientAddress).slice(2, 22);

  logger.verbose(`[${label}] Estimate gas for sending ${amount} to ${wavesRecipientAddress}`);
  const send = contract.methods.sendNative(common.uint8ArrayToBuffer(wavesRecipientPkHashBytes));
  const gasEstimate = await send.estimateGas({
    from: senderAddress,
    value: amount,
  });

  const gasPrice = await ecUtils.estimateGasPrice(ecRpc);
  logger.verbose(`[${label}] Estimated gas: ${gasEstimate}, gas price: ${gasPrice}`);

  const txBaseData = {
    to: contract.options.address,
    from: senderAddress, // provide this or "nonce" field
    gas: gasEstimate * 2n,
    gasPrice: gasPrice,
    value: amount,
    data: send.encodeABI(),
  };

  const txData = { ...txBaseData, ...opts };

  logger.info(`[${label}] Sign %O`, txData);
  const signedCallTxn = await ecRpc.eth.accounts.signTransaction(txData, senderAccount.privateKey);

  logger.verbose(`[${label}] Send %O`, signedCallTxn);
  const txReceipt = await ecRpc.eth.sendSignedTransaction(signedCallTxn.rawTransaction);

  logger.info(`[${label}] Transaction successful with hash: ${txReceipt.transactionHash}`);
  return txReceipt.transactionHash as string;
}

export interface SetupResult {
  ec1Rpc: Web3;
  ec2Rpc: Web3;
  bridgeAddress: string;
  bridgeContract: Contract<ContractAbi>;
  SentEventAbi: any; // TODO: Define a more specific type if available
  sendFromBridge: (label: string, ecRpc: Web3, senderAccount: Web3Account, wavesRecipientAddress: string, amount: string, opts?: { [key: string]: any }) => Promise<string>; // Returns a transaction hash
  utils: typeof ecUtils;
}

export async function setup(): Promise<SetupResult> {
  logger.info('Set up EL');

  logger.info('Wait for EL nodes');
  await ecUtils.waitForHeight(ec1Rpc);

  const abi = JSON.parse(fs.readFileSync('setup/el/compiled/Bridge.abi', { encoding: 'utf8' }));
  const contract = new ec1Rpc.eth.Contract(abi, commonSettings.elBridgeContractAddress);

  function sendFromBridgeF(label: string, ecRpc: Web3, senderAccount: Web3Account, wavesRecipientAddress: string, amount: string, opts?: { [key: string]: any }): Promise<string> {
    return sendFromBridge(contract, label, ecRpc, senderAccount, wavesRecipientAddress, amount, opts);
  };

  logger.info('EL was set up');

  return {
    ec1Rpc,
    ec2Rpc,
    bridgeAddress: commonSettings.elBridgeContractAddress,
    bridgeContract: contract,
    SentEventAbi: contract.options.jsonInterface.find((fragment: any): fragment is AbiEventFragment => fragment.type === 'event' && 'name' in fragment && fragment.name === 'SentNative'),
    sendFromBridge: sendFromBridgeF,
    utils: ecUtils
  };
}
