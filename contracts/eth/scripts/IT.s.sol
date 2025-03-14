// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.28;

import {Script} from "forge-std/Script.sol";
import {TERC20} from "../utils/TERC20.sol";
import {Proxy} from "../src/Proxy.sol";
import {StandardBridge} from "../src/StandardBridge.sol";
import {UnitsMintableERC20} from "../src/UnitsMintableERC20.sol";

contract IT is Script {
    StandardBridge  public standardBridge;
    Proxy public bridgeProxy;
    UnitsMintableERC20 public waves;
    TERC20 public terc20;

    function run() public {
        vm.startBroadcast();

        standardBridge = new StandardBridge();
        bridgeProxy = new Proxy(address(standardBridge), address(1), "");
        waves = new UnitsMintableERC20(address(bridgeProxy), "WAVES", "WAVES", 8);
        terc20 = new TERC20();

        vm.stopBroadcast();
    }
}
