pragma solidity ^0.8.28;

import {ProxyAdmin as OZProxyAdmin} from "@openzeppelin/contracts/proxy/transparent/ProxyAdmin.sol";
import {TransparentUpgradeableProxy} from "@openzeppelin/contracts/proxy/transparent/TransparentUpgradeableProxy.sol";


contract ProxyAdmin is OZProxyAdmin {
    constructor(address initialOwner) OZProxyAdmin(initialOwner) {}
}

contract Proxy is TransparentUpgradeableProxy {
    constructor(address _logic, address initialOwner, bytes memory _data) payable TransparentUpgradeableProxy(_logic, initialOwner, _data) {
    }
}
