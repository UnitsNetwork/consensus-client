// SPDX-License-Identifier: MIT
// Compatible with OpenZeppelin Contracts ^5.0.0
pragma solidity ^0.8.26;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {UnitsMintableERC20} from "@units/UnitsMintableERC20.sol";

contract WWaves is UnitsMintableERC20 {
    constructor(address _bridge)
    UnitsMintableERC20(_bridge, "Wrapped Waves", "WW", 8) {}
}
