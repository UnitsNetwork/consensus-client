// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

contract IssuedTokenBridge {
    uint16 public constant MAX_TRANSFERS_IN_BLOCK = 1024;

    mapping(address => uint256) public balances;
    mapping(uint => uint16) public transfersPerBlock;
    mapping(address => uint64) public tokensRatio;

    // wavesRecipient is a public key hash of recipient account.
    // effectively is it Waves address without 2 first bytes (version and chain id) and last 4 bytes (checksum).
    event ERC20BridgeInitiated(bytes20 wavesRecipient, int64 clAmount, address assetId);
    event ERC20BridgeFinalized(address recipient, int64 clAmount, address assetId);

    event RegistryUpdated(address[] addedAssets, uint8[] addedAssetExponents, address[] removed);

    function updateTokenRegistry(address[] calldata addedAssets, uint8[] calldata addedAssetExponents) external {
        // TODO: add check, that only a miner can do this
        require(addedAssets.length == addedAssetExponents.length, "Different sizes of added assets and their exponents");

        for (uint256 i = 0; i < addedAssets.length; i++) {
            uint8 exponent = addedAssetExponents[i];
            require(exponent <= 10, string.concat("Invalid asset exponent: ", uint2str(uint(exponent))));
            tokensRatio[addedAssets[i]] = uint64(10 ** addedAssetExponents[i]); // log2(10^18) = 59.79... < 64
        }

        emit RegistryUpdated(addedAssets, addedAssetExponents, new address[](0));
    }

    function burn(address recipient, uint256 elAmount) internal {
        // TODO: only bridge can do this
        balances[recipient] -= elAmount;
    }

    // TODO: external for testing purposes, will be internal
    function mint(address recipient, uint256 elAmount) public {
        // TODO: only bridge can do this
        balances[recipient] += elAmount;
    }

    // wavesRecipient is a public key hash of recipient account.
    function bridgeERC20(bytes20 wavesRecipient, uint256 elAmount, address asset) external {
        uint64 ratio = tokensRatio[asset];
        require(ratio > 0, "Token is not registered");

        uint256 minAmountInWei = 1 * ratio;
        uint256 maxAmountInWei = uint256(uint64(type(int64).max)) * ratio;
        require(elAmount >= minAmountInWei, string.concat("Sent value ", uint2str(elAmount), " must be greater or equal to ", uint2str(minAmountInWei)));
        require(elAmount <= maxAmountInWei, string.concat("Sent value ", uint2str(elAmount), " must be less or equal to ", uint2str(maxAmountInWei)));

        uint256 balance = balances[msg.sender];
        require(balance > elAmount, string.concat("Insufficient funds, only ", uint2str(balance), " available"));

        uint256 clAmount = elAmount / ratio;
        require(clAmount * ratio == elAmount, string.concat("Sent value ", uint2str(elAmount), " must be a multiple of ", uint2str(ratio)));

        uint blockNumber = block.number;
        require(transfersPerBlock[blockNumber] < MAX_TRANSFERS_IN_BLOCK, string.concat("Max transfers limit of ", uint2str(uint(MAX_TRANSFERS_IN_BLOCK)), " reached in this block. Try again later"));

        transfersPerBlock[blockNumber]++;
        burn(msg.sender, elAmount);
        emit ERC20BridgeInitiated(wavesRecipient, int64(uint64(clAmount)), asset);
    }

    function finalizeBridgeERC20(address recipient, int64 clAmount, address asset) external {
        // TODO: only miner can do this
        require(clAmount > 0, "Receive value must be greater or equal to 0");

        uint64 ratio = tokensRatio[asset];
        require(ratio > 0, "Token is not registered");

        uint256 elAmount = uint256(int256(clAmount)) * ratio;
        uint256 maxAmountInWei = uint256(uint64(type(int64).max)) * ratio;
        require(elAmount <= maxAmountInWei, "Amount exceeds maximum allowable value");

        // TODO: check amount overflow
        mint(recipient, elAmount);
        emit ERC20BridgeFinalized(recipient, clAmount, asset);
    }

    function uint2str(uint _i) internal pure returns (string memory _uintAsString) {
        if (_i == 0) {
            return "0";
        }
        uint j = _i;
        uint len;
        while (j != 0) {
            len++;
            j /= 10;
        }
        bytes memory bstr = new bytes(len);
        uint k = len;
        while (_i != 0) {
            k = k - 1;
            uint8 temp = (48 + uint8(_i - _i / 10 * 10));
            bytes1 b1 = bytes1(temp);
            bstr[k] = b1;
            _i /= 10;
        }
        return string(bstr);
    }
}
