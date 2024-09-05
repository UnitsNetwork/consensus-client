import { promises as fs } from 'fs';
import * as logger from './logger';

export function sleep(ms: number): Promise<void> {
  return new Promise<void>(resolve => setTimeout(resolve, ms));
}

/**
 * Repeats function call every specified interval until it returns a non-null result.
 * @param f The function to execute repeatedly.
 * @param interval Interval in milliseconds between function calls.
 */
export async function repeat<T>(f: () => Promise<T | undefined>, interval: number): Promise<T> {
  let result: T | undefined;
  while (true) {
    result = await f();
    if (result === null || result === undefined) {
      await sleep(interval);
    } else {
      return result;
    }
  }
}

export async function isInsideDocker(): Promise<boolean> {
  return fs.stat('/.dockerenv').then(() => true).catch(() => false);
}

export function uint8ArrayToBuffer(arr: Uint8Array) {
  return Buffer.from(arr.buffer, arr.byteOffset, arr.byteLength);
}

export async function wrap<T>(p: Promise<T>): Promise<T> {
  try {
    const result = await p;
    return result;
  } catch (e) {
    logger.error(e.message);
    throw e;
  }
}
