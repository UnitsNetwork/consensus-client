// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.28;

import {Script, console} from "forge-std/Script.sol";
import {ProxyAdmin} from "@openzeppelin/contracts/proxy/transparent/ProxyAdmin.sol";
import {ITransparentUpgradeableProxy} from "@openzeppelin/contracts/proxy/transparent/TransparentUpgradeableProxy.sol";
import {StandardBridge} from "../src/StandardBridge.sol";

contract UpgradeStandardBridge is Script {
    address private constant PROXY = 0x2EE5715961C45bd16EB5c2739397B8E871A46F9f;
    address private constant PROXY_ADMIN = 0xff68afF6Cc3e6780e030968e734cD794225D19a6;

    StandardBridge  public standardBridge;

    function run() public {
        vm.startBroadcast();

        standardBridge = new StandardBridge();
        ProxyAdmin(PROXY_ADMIN).upgradeAndCall(ITransparentUpgradeableProxy(payable(PROXY)), address(standardBridge), '');

        vm.stopBroadcast();
    }
}
