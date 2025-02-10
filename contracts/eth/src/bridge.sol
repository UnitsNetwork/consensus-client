// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

contract Bridge {
    address public constant BURN_ADDRESS = address(0);

    // 1 UNIT0(EL) = 1 UNIT0(CL)
    // 10^18 units in 1 UNIT0(EL)
    // 10^8  units in 1 UNIT0(CL)
    // 10^(18-8) = UNIT0(EL) / UNIT0(CL) = ratio
    // Conversion: N UNIT0(CL) = UNIT0(EL) / ratio
    uint256 public constant EL_TO_CL_RATIO = 10 ** 10; // 10 * 1 Gwei = 10 Gwei

    uint256 public constant MIN_AMOUNT_IN_WEI = 1 * EL_TO_CL_RATIO;
    uint256 public constant MAX_AMOUNT_IN_WEI = uint256(uint64(type(int64).max)) * EL_TO_CL_RATIO;

    uint16 public constant MAX_TRANSFERS_IN_BLOCK = 1024;

    mapping(uint => uint16) public transfersPerBlock;

    // wavesRecipient is a public key hash of recipient account.
    // effectively is it Waves address without 2 first bytes (version and chain id) and last 4 bytes (checksum).
    event SentNative(bytes20 wavesRecipient, int64 amount);

    function sendNative(bytes20 wavesRecipient) external payable {
        require(msg.value >= MIN_AMOUNT_IN_WEI, string.concat("Sent value ", uint2str(msg.value), " must be greater or equal to ", uint2str(MIN_AMOUNT_IN_WEI)));
        require(msg.value <= MAX_AMOUNT_IN_WEI, string.concat("Sent value ", uint2str(msg.value), " must be less or equal to ", uint2str(MAX_AMOUNT_IN_WEI)));

        uint blockNumber = block.number;
        require(transfersPerBlock[blockNumber] < MAX_TRANSFERS_IN_BLOCK, string.concat("Max transfers limit of ", uint2str(uint(MAX_TRANSFERS_IN_BLOCK)), " reached in this block. Try to send transfers again"));
        transfersPerBlock[blockNumber]++;

        uint256 ccAmount = msg.value / EL_TO_CL_RATIO;
        require(ccAmount * EL_TO_CL_RATIO == msg.value, string.concat("Sent value ", uint2str(msg.value), " must be a multiple of ", uint2str(EL_TO_CL_RATIO)));

        (bool success,) = BURN_ADDRESS.call{value: msg.value}("");
        require(success, "Failed to send to burn address");

        emit SentNative(wavesRecipient, int64(uint64(ccAmount)));
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
