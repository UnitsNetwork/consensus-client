import * as logger from '../src/logger';
import { setup } from '../setup';

const { waves } = await setup(false);

logger.info('Join a miner with the balance of 50% + 1');

const unsignedTxnJson = {
  sender: "3FSrRN8X7cDsLyYTScS8Yf8KSwZgJBwf1jU",
  feeAssetId: null,
  fee: 2400000,
  version: 2,
  type: 12,
  data: [
    {
      key: "%s__3FSsLw2bximwiBGP1KNTjbTHJVgYiNBojrS",
      type: "string",
      value: "%d%d%d%d__9__5500001__40__5500001"
    }
  ]
};

await waves.utils.signAndBroadcast(waves.wavesApi2, 'join big', unsignedTxnJson, { wait: true });
