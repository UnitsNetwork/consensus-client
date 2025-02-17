// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.10;

import {Script, console} from "forge-std/Script.sol";
import {StandardBridge} from "../src/StandardBridge.sol";
import {TERC20} from "../utils/TERC20.sol";
import {WWaves} from "../utils/WWaves.sol";

contract Deployer is Script {
    StandardBridge public standardBridge;
    TERC20 public terc20;
    WWaves public wwaves;

    function setUp() public {}

    function run() public {
        vm.startBroadcast();

        terc20 = new TERC20();
        wwaves = new WWaves(0x0000000000000000000000000000000057d06A7E);

        vm.stopBroadcast();
    }
}
