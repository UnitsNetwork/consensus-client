import * as all from "./src/common-settings";
import * as n from "./src/nodes";

console.log(all);

const elBridgeAddressResponse = await n.wavesApi1.addresses.fetchDataKey(all.chainContract.address, 'elBridgeAddress');
if (!elBridgeAddressResponse.value || elBridgeAddressResponse.type != 'string') throw new Error(`Unexpected value of "elBridgeAddress" contract key in response: ${elBridgeAddressResponse}`);
const elBridgeAddress = elBridgeAddressResponse.value;

console.log(`EL bridge address from contract: ${elBridgeAddress}`);

