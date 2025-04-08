// SPDX-License-Identifier: MIT
// Compatible with OpenZeppelin Contracts ^5.0.0
pragma solidity ^0.8.26;

import { UnitsMintableERC20 } from "@units/UnitsMintableERC20.sol";

contract TWWaves is UnitsMintableERC20 {
    /// @param _bridge      Address of the L2 standard bridge.
    constructor(address _bridge)
    UnitsMintableERC20(_bridge, "WAVES", "WAVES", 8)
    {
        _mint(0xFE3B557E8Fb62b89F4916B721be55cEb828dBd73, 10 ** 11); // rich account 1
        _mint(0xf17f52151EbEF6C7334FAD080c5704D77216b732, 10 ** 11); // rich account 2
    }
}
