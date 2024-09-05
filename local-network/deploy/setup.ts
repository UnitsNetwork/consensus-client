// Monkey patching: https://github.com/GoogleChromeLabs/jsbi/issues/30#issuecomment-1006088574
// eslint-disable-next-line @typescript-eslint/no-redeclare
declare global {
  interface BigInt {
    /** Convert to BigInt to string form in JSON.stringify */
    toJSON: () => string;
  }
}
BigInt.prototype.toJSON = function() {
  return this.toString();
};

// import {EventEmitter} from 'events';
import * as common from './src/common';
import { setup as setupEl, SetupResult as SetupElResult } from './src/el';
import { setup as setupWaves, SetupResult as SetupWavesResult } from './src/waves';

// EventEmitter.prototype.setMaxListeners(20); // HACK

export interface SetupResult {
  waves: SetupWavesResult;
  el: SetupElResult;
}

export async function setup(force: boolean): Promise<SetupResult> {
  return {
    waves: await common.wrap(setupWaves(force)),
    el: await common.wrap(setupEl())
  }
}
