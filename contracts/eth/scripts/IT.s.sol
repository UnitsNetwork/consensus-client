// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.28;

import {Script} from "forge-std/Script.sol";
import {TransparentUpgradeableProxy} from "@openzeppelin/contracts/proxy/transparent/TransparentUpgradeableProxy.sol";
import {TERC20} from "../src/utils/TERC20.sol";
import {StandardBridge} from "../src/StandardBridge.sol";
import {TWWaves} from "../src/utils/TWWaves.sol";

contract IT is Script {
    StandardBridge  public standardBridge;
    TransparentUpgradeableProxy public bridgeProxy;
    TWWaves public waves;
    TERC20 public terc20;

    function run() public {
        vm.startBroadcast();

        standardBridge = new StandardBridge();
        bridgeProxy = new TransparentUpgradeableProxy(address(standardBridge), address(1), "");
        waves = new TWWaves(address(bridgeProxy));
        terc20 = new TERC20();

        vm.stopBroadcast();
    }
}
