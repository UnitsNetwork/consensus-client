// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

contract IssuedTokenBridge {
    // See explanation in bridge.sol
    uint256 public constant EL_TO_CL_RATIO = 10 ** 10; // 10 * 1 Gwei = 10 Gwei

    uint256 public constant MIN_AMOUNT_IN_WEI = 1 * EL_TO_CL_RATIO;
    uint256 public constant MAX_AMOUNT_IN_WEI = uint256(uint64(type(int64).max)) * EL_TO_CL_RATIO;

    uint16 public constant MAX_TRANSFERS_IN_BLOCK = 1024;

    mapping(address => uint256) public balances;
    mapping(uint => uint16) public transfersPerBlock;
    mapping(address => bool) public tokenRegistry;

    // wavesRecipient is a public key hash of recipient account.
    // effectively is it Waves address without 2 first bytes (version and chain id) and last 4 bytes (checksum).
    event ERC20BridgeInitiated(bytes20 wavesRecipient, int64 clAmount, address assetId);
    event ERC20BridgeFinalized(address recipient, int64 clAmount, address assetId);

    event RegistryUpdated(address[] added, address[] removed);

    function updateTokenRegistry(address[] calldata added) external {
        // TODO: only miner can do this
        for (uint256 i = 0; i < added.length; i++) {
            tokenRegistry[added[i]] = true;
        }

        emit RegistryUpdated(added, new address[](0));
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
    function bridgeERC20(bytes20 wavesRecipient, uint256 elAmount) external {
        require(elAmount >= MIN_AMOUNT_IN_WEI, string.concat("Sent value ", uint2str(elAmount), " must be greater or equal to ", uint2str(MIN_AMOUNT_IN_WEI)));
        require(elAmount <= MAX_AMOUNT_IN_WEI, string.concat("Sent value ", uint2str(elAmount), " must be less or equal to ", uint2str(MAX_AMOUNT_IN_WEI)));

        uint256 balance = balances[msg.sender];
        require(balance > 0, string.concat("Insufficient funds, only ", uint2str(balance), " available"));

        uint256 clAmount = elAmount / EL_TO_CL_RATIO;
        require(clAmount * EL_TO_CL_RATIO == elAmount, string.concat("Sent value ", uint2str(elAmount), " must be a multiple of ", uint2str(EL_TO_CL_RATIO)));

        uint blockNumber = block.number;
        require(transfersPerBlock[blockNumber] < MAX_TRANSFERS_IN_BLOCK, string.concat("Max transfers limit of ", uint2str(uint(MAX_TRANSFERS_IN_BLOCK)), " reached in this block. Try again later"));
        transfersPerBlock[blockNumber]++;

        burn(msg.sender, elAmount);
        emit ERC20BridgeInitiated(wavesRecipient, int64(uint64(clAmount)), address(this));
    }

    function finalizeBridgeERC20(address recipient, int64 clAmount) external {
        // TODO: only miner can do this
        require(clAmount > 0, "Receive value must be greater or equal to 0");

        uint256 elAmount = uint256(int256(clAmount)) * EL_TO_CL_RATIO;
        require(elAmount <= MAX_AMOUNT_IN_WEI, "Amount exceeds maximum allowable value");

        mint(recipient, elAmount);
        emit ERC20BridgeFinalized(recipient, clAmount, address(this));
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
