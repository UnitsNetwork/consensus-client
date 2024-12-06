// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

contract BridgeUser {
    // See explanation in bridge.sol
    uint256 public constant EL_TO_CL_RATIO = 10 ** 10; // 10 * 1 Gwei = 10 Gwei

    uint256 public constant MIN_AMOUNT_IN_WEI = 1 * EL_TO_CL_RATIO;
    uint256 public constant MAX_AMOUNT_IN_WEI = uint256(uint64(type(int64).max)) * EL_TO_CL_RATIO;

    uint16 public constant MAX_TRANSFERS_IN_BLOCK = 1024;

    mapping(address => uint256) public balances;
    mapping(uint => uint16) public transfersPerBlock;

    // wavesRecipient is a public key hash of recipient account.
    // effectively is it Waves address without 2 first bytes (version and chain id) and last 4 bytes (checksum).
    event SentIssued(bytes20 wavesRecipient, int64 amount);
    event ReceivedIssued(address indexed receiver, uint256 amount);

    // wavesRecipient is a public key hash of recipient account.
    function sendIssued(bytes20 wavesRecipient, uint256 amount) external {
        require(amount >= MIN_AMOUNT_IN_WEI, string.concat("Sent value ", uint2str(amount), " must be greater or equal to ", uint2str(MIN_AMOUNT_IN_WEI)));
        require(amount <= MAX_AMOUNT_IN_WEI, string.concat("Sent value ", uint2str(amount), " must be less or equal to ", uint2str(MAX_AMOUNT_IN_WEI)));

        uint256 balance = balances[msg.sender];
        require(balance > 0, string.concat("Insufficient funds, only ", uint2str(balance), " available"));

        uint256 ccAmount = amount / EL_TO_CL_RATIO;
        require(ccAmount * EL_TO_CL_RATIO == amount, string.concat("Sent value ", uint2str(amount), " must be a multiple of ", uint2str(EL_TO_CL_RATIO)));

        uint blockNumber = block.number;
        require(transfersPerBlock[blockNumber] < MAX_TRANSFERS_IN_BLOCK, string.concat("Max transfers limit of ", uint2str(uint(MAX_TRANSFERS_IN_BLOCK)), " reached in this block. Try again later"));
        transfersPerBlock[blockNumber]++;

        balances[msg.sender] -= amount;
        emit SentIssued(wavesRecipient, int64(uint64(ccAmount)));
    }

    function receiveIssued(address receiver, int64 amount) external {
        // TODO: check caller address?
        require(amount > 0, "Receive value must be greater or equal to 0");

        uint256 convertedAmount = uint256(int256(amount)) * EL_TO_CL_RATIO;
        require(convertedAmount <= MAX_AMOUNT_IN_WEI, "Amount exceeds maximum allowable value");

        balances[receiver] += convertedAmount;
        emit ReceivedIssued(receiver, convertedAmount);
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
