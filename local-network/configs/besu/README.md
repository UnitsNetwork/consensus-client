# Besu configs

Use [./generate-configs.sh](generate-configs.sh) to generate `genesis.json` and keys for nodes.
Note: it always generates new keys! So, don't use it if you don't need to add nodes.

## Manual connecting

Follow [https://besu.hyperledger.org/stable/private-networks/tutorials/permissioning](instructions):

1. Specify `--permissions-nodes-config-file=/opt/permissions_config.toml` in compose under `command`
2. Mount a file to this path:
   ```toml
   accounts-allowlist=["0xfe3b557e8fb62b89f4916b721be55ceb828dbd73", "0xf17f52151EbEF6C7334FAD080c5704D77216b732"]
   nodes-allowlist=[]
   ```
   Take `accounts-allowlist` from `alloc` of `genesis.json`. Don't forget to add `0x`.
3. Connect nodes at any time using script like this:
   ```bash
   NODE_1_ENODE=$(curl -s -d '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' http://0:18545 | jq -r .result.enode)
   NODE_2_ENODE=$(curl -s -d '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' http://0:28545 | jq -r .result.enode)

   curl -X POST --data '{"jsonrpc":"2.0","method":"admin_addPeer","params":["'"${NODE_2_ENODE}"'"], "id":1}' http://127.0.0.1:18545
   curl -X POST --data '{"jsonrpc":"2.0","method":"admin_addPeer","params":["'"${NODE_1_ENODE}"'"], "id":1}' http://127.0.0.1:28545

   curl -X POST --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":51}' http://127.0.0.1:18545
   curl -X POST --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":53}' http://127.0.0.1:18545
   ```

# Check peers

Expected output for:

```bash
curl -X POST --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":53}' http://127.0.0.1:18545
```

is:

```json
{
  "jsonrpc": "2.0",
  "id": 53,
  "result": "0x1"
}
```

- _result_ > 0
 