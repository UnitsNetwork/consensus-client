// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.28;

import {Script, console} from "forge-std/Script.sol";
import {TransparentUpgradeableProxy} from "@openzeppelin/contracts/proxy/transparent/TransparentUpgradeableProxy.sol";
import {StandardBridge} from "../src/StandardBridge.sol";
import {UnitsMintableERC20} from "../src/UnitsMintableERC20.sol";

contract DeployUnit is Script {
    StandardBridge  public standardBridge;
    TransparentUpgradeableProxy public bridgeProxy;
    UnitsMintableERC20 public waves;

    function run() public {
        address bridgeProxyAdmin = vm.envAddress("BRIDGE_PROXY_ADMIN");
        vm.startBroadcast();

        standardBridge = new StandardBridge();
        bridgeProxy = new TransparentUpgradeableProxy(address(standardBridge), bridgeProxyAdmin, "");
        waves = new UnitsMintableERC20(address(bridgeProxy), "WAVES", "WAVES", 8);

        vm.stopBroadcast();
    }
}
