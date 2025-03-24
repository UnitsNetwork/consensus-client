// SPDX-License-Identifier: MIT
pragma solidity ^0.8.29;

import { IERC165 } from "@openzeppelin/contracts/utils/introspection/IERC165.sol";

/// @title IUnitsMintableERC20
/// @notice This interface is available on the UnitsMintableERC20 contract.
///         We declare it as a separate interface so that it can be used in
///         custom implementations of UnitsMintableERC20.
interface IUnitsMintableERC20 is IERC165 {
    function mint(address _to, uint256 _amount) external;

    function burn(address _from, uint256 _amount) external;
}
