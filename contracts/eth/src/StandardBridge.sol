// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {ERC165Checker} from "@openzeppelin/contracts/utils/introspection/ERC165Checker.sol";
import {Address} from "@openzeppelin/contracts/utils/Address.sol";
import {Strings} from "@openzeppelin/contracts/utils/Strings.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {IUnitsMintableERC20} from "@units/IUnitsMintableERC20.sol";

contract StandardBridge {
    using SafeERC20 for IERC20;

    /// @notice Mapping that stores deposits for a given local token.
    mapping(address => uint256) public deposits;
    mapping(address => uint256) public tokenRatios;

    event ERC20BridgeInitiated(
        address indexed localToken,
        address indexed from,
        address indexed clTo,
        int64 clAmount
    );

    event ERC20BridgeFinalized(
        address indexed localToken,
        address indexed from,
        address indexed elTo,
        uint256 amount
    );

    event RegistryUpdated(address[] addedTokens, uint8[] addedTokenExponents, address[] removedTokens);

    /// @notice Ensures that the caller is an empty address.
    modifier onlyMiner() {
        require(
            msg.sender == address(0),
            "StandardBridge: function can only be called by the miner"
        );
        _;
    }

    function updateAssetRegistry(address[] calldata addedTokens, uint8[] calldata addedTokenExponents) external onlyMiner {
        require(addedTokens.length == addedTokenExponents.length, "Different sizes of added tokens and their exponents");

        for (uint256 i = 0; i < addedTokens.length; i++) {
            tokenRatios[addedTokens[i]] = uint256(10 ** addedTokenExponents[i]);
        }

        emit RegistryUpdated(addedTokens, addedTokenExponents, new address[](0));
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
        uint256 ratio = tokenRatios[_localToken];
        require(ratio > 0, "Token is not registered");

        uint256 minAmountInWei = 1 * ratio;
        uint256 maxAmountInWei = uint256(uint64(type(int64).max)) * ratio;
        require(_amount >= minAmountInWei, string.concat("Sent value ", Strings.toString(_amount), " must be greater or equal to ", Strings.toString(minAmountInWei)));
        require(_amount <= maxAmountInWei, string.concat("Sent value ", Strings.toString(_amount), " must be less or equal to ", Strings.toString(maxAmountInWei)));

        uint256 dust = _amount % ratio;
        uint256 adjustedAmount = _amount - dust;
        require(adjustedAmount > 0, "Adjusted amount is empty. Try to send more");

        if (_isUnitsMintableERC20(_localToken)) {
            IUnitsMintableERC20(_localToken).burn(_from, adjustedAmount);
        } else {
            IERC20(_localToken).safeTransferFrom(_from, address(this), adjustedAmount);
            deposits[_localToken] += adjustedAmount;
        }

        // Emit the correct events. By default this will be ERC20BridgeInitiated, but child
        // contracts may override this function in order to emit legacy events as well.
        uint256 clAmount = adjustedAmount / ratio;
        _emitERC20BridgeInitiated(_localToken, _from, _to, clAmount);
    }

    /// @notice Finalizes an ERC20 bridge on this chain. Can only be triggered by the other
    ///         StandardBridge contract on the remote chain.
    /// @param _localToken  Address of the ERC20 on this chain.
    /// @param _from        Address of the sender.
    /// @param _to          Address of the receiver.
    /// @param _amount      Amount of the ERC20 being bridged.
    function finalizeBridgeERC20(
        address _localToken,
        address _from,
        address _to,
        uint256 _amount
    )
    public
    onlyMiner
    {
        if (_isUnitsMintableERC20(_localToken)) {
            IUnitsMintableERC20(_localToken).mint(_to, _amount);
        } else {
            deposits[_localToken] = deposits[_localToken] - _amount;
            IERC20(_localToken).safeTransfer(_to, _amount);
        }

        // Emit the correct events. By default this will be ERC20BridgeFinalized, but child
        // contracts may override this function in order to emit legacy events as well.
        _emitERC20BridgeFinalized(_localToken, _from, _to, _amount);
    }

    /// @notice Emits the ERC20BridgeFinalized event and if necessary the appropriate legacy
    ///         event when an ERC20 bridge is initiated to the other chain.
    /// @param _localToken  Address of the ERC20 on this chain.
    /// @param _from        Address of the sender.
    /// @param _to          Address of the receiver.
    /// @param _amount      Amount of the ERC20 sent.
    function _emitERC20BridgeFinalized(
        address _localToken,
        address _from,
        address _to,
        uint256 _amount
    )
    internal
    virtual
    {
        emit ERC20BridgeFinalized(_localToken, _from, _to, _amount);
    }
}
