// SPDX-License-Identifier: MIT
// Compatible with OpenZeppelin Contracts ^5.0.0
pragma solidity ^0.8.26;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";

contract TERC20 is ERC20 {
    constructor() ERC20("TERC20", "TTK") {
        _mint(0xFE3B557E8Fb62b89F4916B721be55cEb828dBd73, 10 ** 21); // rich account 1
        _mint(0xf17f52151EbEF6C7334FAD080c5704D77216b732, 10 ** 21); // rich account 2
    }
}
