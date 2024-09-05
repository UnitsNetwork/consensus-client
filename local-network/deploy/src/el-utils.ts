import Web3 from 'web3';
import * as common from './common';
import * as logger from './logger';

export const MIN_BLOCKS = 2;

/**
 * Waits until the current block number is at least the given height.
 * @param web3 The Web3 instance with registered subscription types.
 * @param height The block number to wait for.
 */
export async function waitForHeight(web3: Web3<any>, height: number = MIN_BLOCKS): Promise<void> {
  while (true) {
    try {
      const currentBlockNumber = await web3.eth.getBlockNumber();
      if (currentBlockNumber >= height) return;
      logger.debug(`Current block number is ${currentBlockNumber}`);
    } catch (e) {
      logger.debug('API is not available');
    }
    await common.sleep(3000);
  }
}

/**
 * Waits for a transaction to be included in a block indefinitely using the repeat function.
 * @param web3 The Web3 instance with registered subscription types.
 * @param transactionHash The hash of the transaction to wait for.
 * @param interval Interval in milliseconds to check for the transaction receipt.
 */
export async function waitForTransaction(web3: Web3<any>, transactionHash: string, interval: number = 1000): Promise<any> {
  const getReceipt = async () => await web3.eth.getTransactionReceipt(transactionHash);
  return await common.repeat(getReceipt, interval);
}

/**
 * Estimates the gas price based on recent blocks and current gas price.
 * @param web3 The Web3 instance with registered subscription types.
 * @param minBlocks The minimum number of blocks to consider for calculating the average gas price.
 */
export async function estimateGasPrice(web3: Web3<any>, minBlocks: number = MIN_BLOCKS): Promise<bigint> {
  const height = Number(await web3.eth.getBlockNumber());
  const blockCount = Math.max(minBlocks, Math.min(20, height));

  const gasPriceByPpi = BigInt(await web3.eth.getGasPrice());

  // Bad schema in web3:
  // @ts-ignore: Type 'bigint' is not assignable to type 'bigint[]'
  const gasPricesHistory: bigint[] = (await web3.eth.getFeeHistory(blockCount, 'latest', [])).baseFeePerGas;

  let gasPrice: bigint;
  if (gasPricesHistory.length > 0) {
    const averagePrice = gasPricesHistory.reduce((acc, fee) => acc + fee, 0n) / BigInt(gasPricesHistory.length);
    gasPrice = averagePrice > gasPriceByPpi ? averagePrice : gasPriceByPpi;
  } else {
    gasPrice = gasPriceByPpi;
  }

  return gasPrice * 110n / 100n; // + 10% tip
}
