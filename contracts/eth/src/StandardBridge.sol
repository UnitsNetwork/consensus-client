// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

import { IERC20 } from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import { ERC165Checker } from "@openzeppelin/contracts/utils/introspection/ERC165Checker.sol";
import { Address } from "@openzeppelin/contracts/utils/Address.sol";
import { Strings } from "@openzeppelin/contracts/utils/Strings.sol";
import { SafeERC20 } from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import { IUnitsMintableERC20 } from "src/IUnitsMintableERC20.sol";

contract StandardBridge {
    using SafeERC20 for IERC20;
    uint16 public constant MAX_TRANSFERS_IN_BLOCK = 1024;

    /// @notice Mapping that stores deposits for a given local token.
    mapping(address => uint256) public deposits;

    mapping(address => uint256) public balances;
    mapping(uint => uint16)     public transfersPerBlock;
    mapping(address => uint64)  public tokenRatios;

    event ERC20BridgeInitiated(
        address indexed localToken,
        address indexed from,
        address indexed clTo,
        int64 clAmount
    );

    event ERC20BridgeFinalized(address indexed localToken, address indexed elTo, int64 clAmount);

    event RegistryUpdated(address[] addedTokens, uint8[] addedTokenExponents, address[] removedTokens);

    function updateAssetRegistry(address[] calldata addedTokens, uint8[] calldata addedTokenExponents) external {
        // TODO: add check, that only a miner can do this
        require(addedTokens.length == addedTokenExponents.length, "Different sizes of added tokens and their exponents");

        for (uint256 i = 0; i < addedTokens.length; i++) {
            uint8 exponent = addedTokenExponents[i];
            require(exponent <= 10, string.concat("Invalid token exponent: ", Strings.toString(uint(exponent))));
            tokenRatios[addedTokens[i]] = uint64(10 ** addedTokenExponents[i]); // log2(10^18) = 59.79... < 64
        }

        emit RegistryUpdated(addedTokens, addedTokenExponents, new address[](0));
    }

    function burn(address from, uint256 elAmount) internal {
        // TODO: only bridge can do this
        balances[from] -= elAmount;
    }

    // TODO: external for testing purposes, will be internal
    function mint(address to, uint256 elAmount) public {
        // TODO: only bridge can do this
        balances[to] += elAmount;
    }

    // clTo is a public key hash of recipient account.
    function bridgeERC20(address token, address clTo, uint256 elAmount) external {
        _initiateBridgeERC20(token, msg.sender, clTo, elAmount);
    }

    /// @notice Checks if a given address is an OptimismMintableERC20. Not perfect, but good enough.
    ///         Just the way we like it.
    /// @param _token Address of the token to check.
    /// @return True if the token is an OptimismMintableERC20.
    function _isUnitsMintableERC20(address _token) internal view returns (bool) {
        return ERC165Checker.supportsInterface(_token, type(IUnitsMintableERC20).interfaceId);
    }

    /// @notice Emits the ERC20BridgeInitiated event and if necessary the appropriate legacy
    ///         event when an ERC20 bridge is initiated to the other chain.
    /// @param _localToken  Address of the ERC20 on this chain.
    /// @param _from        Address of the sender.
    /// @param _to          Address of the receiver.
    /// @param _amount      Amount of the ERC20 sent.
    function _emitERC20BridgeInitiated(
        address _localToken,
        address _from,
        address _to,
        uint256 _amount
    )
    internal
    virtual
    {
        emit ERC20BridgeInitiated(_localToken, _from, _to, int64(uint64(_amount)));
    }

    /// @notice Sends ERC20 tokens to a receiver's address on the other chain.
    /// @param _localToken  Address of the ERC20 on this chain.
    /// @param _to          Address of the receiver.
    /// @param _amount      Amount of local tokens to deposit.
    function _initiateBridgeERC20(
        address _localToken,
        address _from,
        address _to,
        uint256 _amount
    )
    internal
    {
        uint64 ratio = tokenRatios[_localToken];
        require(ratio > 0, "Token is not registered");

        uint256 minAmountInWei = 1 * ratio;
        uint256 maxAmountInWei = uint256(uint64(type(int64).max)) * ratio;
        require(_amount >= minAmountInWei, string.concat("Sent value ", Strings.toString(_amount), " must be greater or equal to ", Strings.toString(minAmountInWei)));
        require(_amount <= maxAmountInWei, string.concat("Sent value ", Strings.toString(_amount), " must be less or equal to ", Strings.toString(maxAmountInWei)));

        uint256 balance = balances[msg.sender];
        require(balance > _amount, string.concat("Insufficient funds, only ", Strings.toString(balance), " available"));

        uint256 clAmount = _amount / ratio;
        require(clAmount * ratio == _amount, string.concat("Sent value ", Strings.toString(_amount), " must be a multiple of ", Strings.toString(ratio)));

        if (_isUnitsMintableERC20(_localToken)) {
            IUnitsMintableERC20(_localToken).burn(_from, _amount);
        } else {
            IERC20(_localToken).safeTransferFrom(_from, address(this), _amount);
            deposits[_localToken] = deposits[_localToken] + _amount;
        }

        // Emit the correct events. By default this will be ERC20BridgeInitiated, but child
        // contracts may override this function in order to emit legacy events as well.
        _emitERC20BridgeInitiated(_localToken, _from, _to, clAmount);
    }

    function finalizeBridgeERC20(address token, address elTo, int64 clAmount) external {
        // TODO: only miner can do this
        require(clAmount > 0, "Receive value must be greater or equal to 0");

        uint64 ratio = tokenRatios[token];
        require(ratio > 0, "Token is not registered");

        uint256 elAmount = uint256(int256(clAmount)) * ratio;
        uint256 maxAmountInWei = uint256(uint64(type(int64).max)) * ratio;
        require(elAmount <= maxAmountInWei, "Amount exceeds maximum allowable value");

        // TODO: check amount overflow
        mint(elTo, elAmount);
        emit ERC20BridgeFinalized(token, elTo, clAmount);
    }
}
