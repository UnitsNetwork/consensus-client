#!/usr/bin/env .venv/bin/python
import os


from units_network import common_utils

from network import get_network
from accounts import accounts


def main():
    log = common_utils.configure_script_logger(os.path.basename(__file__))
    network = get_network()

    cl_account = accounts.waves_miners[0].account
    el_account = network.w3.eth.account.from_key(
        "0x6f077b245cb0e1ec59796366ebd3a254e604cf5686b64b7878ae730eb9ad9570"
    )

    user_amount = "0.01"
    # Issued token has 8 decimals, we need to calculate amount in atomic units https://docs.waves.tech/en/blockchain/token/#atomic-unit
    atomic_amount = int(float(user_amount) * 10**8)

    log.info(
        f"Sending {user_amount} Unit0 ({atomic_amount} in atomic units) from {cl_account.address} (C) to {el_account.address} (E)"
    )

    token = network.cl_chain_contract.getToken()
    log.info(f"[C] Token id: {token.assetId}")

    # TODO el_account -> el_address
    transfer_result = network.cl_chain_contract.transfer(
        cl_account, el_account, token, atomic_amount
    )
    log.info(f"[C] Transfer result: {transfer_result}")
    log.info("Done")


if __name__ == "__main__":
    main()


# import { setup } from '../setup';
# import * as common from '../src/common';
# import * as logger from '../src/logger';
# import * as wavesTxs from '../src/waves-txs';
# import * as s from '../src/common-settings';
#
# const { waves, el } = await setup(false);
#
# logger.info("Running EL->CL transfer test");
#
# const recipientAccountPrivateKey = '0x6f077b245cb0e1ec59796366ebd3a254e604cf5686b64b7878ae730eb9ad9570';
# const recipientAccount = el.ec1Rpc.eth.accounts.privateKeyToAccount(recipientAccountPrivateKey);
# logger.info(`Recipient account address: ${recipientAccount.address}`);
#
# const sender1 = s.wavesMiner1;
# const sendingAmount = 1_000_000n;
#
# async function assetBalance(addr: string): Promise<bigint> {
#   const r = await waves.wavesApi1.assets.fetchBalanceAddressAssetId(addr, waves.tokenId);
#   return BigInt(r.balance);
# }
#
# const recipient1Balance = await assetBalance(sender1.address);
#
# logger.info(`${sender1.address} balance: ${recipient1Balance}`);
#
# const balanceBefore = await el.ec1Rpc.eth.getBalance(recipientAccount.address);
# logger.debug(`Balance before of ${recipientAccount.address}: ${balanceBefore}`);
#
# const transfersNumber = 18;
# let ids: string[] = [];
# let now = Date.now();
# for (let i = 1; i <= transfersNumber; i++) {
#   const txn = wavesTxs.mkC2ETransfer(sender1.privateKey, recipientAccount.address, sendingAmount + BigInt(i), waves.tokenId, now + i);
#   logger.debug(`Broadcast transfer ${txn.id}`);
#   waves.wavesApi1.transactions.broadcast(txn);
#   ids.push(txn.id);
# }
#
# for (let id of ids) {
#   await waves.utils.waitForTxn(waves.wavesApi1, id);
# }
#
# let currHeight = await el.ec1Rpc.eth.getBlockNumber();
# let sendingAmountIntWei = sendingAmount * 10n; // 1 unit is 10 Gwei, see bridge.sol
#
# const transferBlock = await common.repeat(async () => {
#   const currBlock = await el.ec1Rpc.eth.getBlock(currHeight, false);
#   if (currBlock) {
#     logger.info(`Found block #${currBlock.number}: ${currBlock.hash}`);
#     const i = currBlock.withdrawals.findIndex((w: any) => {
#       return w.address == recipientAccount.address.toLowerCase() && w.amount >= sendingAmountIntWei;
#     });
#
#     if (i >= 0) return currBlock;
#     currHeight += 1n;
#   }
# }, 3000);
#
# const balanceAfter = await el.ec1Rpc.eth.getBalance(recipientAccount.address);
# logger.info(`Found transfers in ${transferBlock.hash}, balance after transfer: ${balanceAfter}, transferred: ${balanceAfter - balanceBefore} tokens`);
#
# logger.info('Done');
#
#
