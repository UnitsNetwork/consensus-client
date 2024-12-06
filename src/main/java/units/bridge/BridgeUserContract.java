//package units.bridge;
//
//import io.reactivex.Flowable;
//import java.math.BigInteger;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Collections;
//import java.util.List;
//import org.web3j.abi.EventEncoder;
//import org.web3j.abi.TypeReference;
//import org.web3j.abi.datatypes.Address;
//import org.web3j.abi.datatypes.Event;
//import org.web3j.abi.datatypes.Function;
//import org.web3j.abi.datatypes.Type;
//import org.web3j.abi.datatypes.generated.Bytes20;
//import org.web3j.abi.datatypes.generated.Uint256;
//import org.web3j.abi.datatypes.primitive.Int;
//import org.web3j.abi.datatypes.primitive.Long;
//import org.web3j.crypto.Credentials;
//import org.web3j.protocol.Web3j;
//import org.web3j.protocol.core.DefaultBlockParameter;
//import org.web3j.protocol.core.RemoteCall;
//import org.web3j.protocol.core.RemoteFunctionCall;
//import org.web3j.protocol.core.methods.request.EthFilter;
//import org.web3j.protocol.core.methods.response.BaseEventResponse;
//import org.web3j.protocol.core.methods.response.Log;
//import org.web3j.protocol.core.methods.response.TransactionReceipt;
//import org.web3j.tx.Contract;
//import org.web3j.tx.TransactionManager;
//import org.web3j.tx.gas.ContractGasProvider;
//
///**
// * <p>Auto generated code.
// * <p><strong>Do not modify!</strong>
// * <p>Please use the <a href="https://docs.web3j.io/command_line.html">web3j command line tools</a>,
// * or the org.web3j.codegen.SolidityFunctionWrapperGenerator in the
// * <a href="https://github.com/hyperledger/web3j/tree/main/codegen">codegen module</a> to update.
// *
// * <p>Generated with web3j version 1.6.1.
// */
//@SuppressWarnings("rawtypes")
//public class BridgeUserContract extends Contract {
//    public static final String BINARY = "6080604052348015600e575f80fd5b50610abc8061001c5f395ff3fe608060405234801561000f575f80fd5b5060043610610085575f3560e01c806396f396c31161005857806396f396c31461010f578063b92ab41014610117578063c4a4326d1461012a578063e984df0e14610136575f80fd5b806327e235e31461008957806339dd5d1b146100bb578063596a9204146100f15780637157405a14610106575b5f80fd5b6100a86100973660046106f0565b5f6020819052908152604090205481565b6040519081526020015b60405180910390f35b6100de6100c9366004610710565b60016020525f908152604090205461ffff1681565b60405161ffff90911681526020016100b2565b6101046100ff366004610727565b61013e565b005b6100de61040081565b6100a86102a3565b610104610125366004610761565b6102bd565b6100a86402540be40081565b6100a861059f565b5f8160070b136101a95760405162461bcd60e51b815260206004820152602b60248201527f526563656976652076616c7565206d7573742062652067726561746572206f7260448201526a020657175616c20746f20360ac1b60648201526084015b60405180910390fd5b5f6101bd6402540be400600784900b6107b0565b90506101d66402540be400677fffffffffffffff6107b0565b8111156102345760405162461bcd60e51b815260206004820152602660248201527f416d6f756e742065786365656473206d6178696d756d20616c6c6f7761626c656044820152652076616c756560d01b60648201526084016101a0565b6001600160a01b0383165f908152602081905260408120805483929061025b9084906107cd565b90915550506040518181526001600160a01b038416907f59f5876c045465d752b627947d60f823a14a628d7a06fefdef5e070d1cc0baa29060200160405180910390a2505050565b6102ba6402540be400677fffffffffffffff6107b0565b81565b6102cd6402540be40060016107b0565b8110156102d9826105af565b6102f16102ec6402540be40060016107b0565b6105af565b6040516020016103029291906107f7565b6040516020818303038152906040529061032f5760405162461bcd60e51b81526004016101a0919061084f565b506103476402540be400677fffffffffffffff6107b0565b811115610353826105af565b61036d6102ec6402540be400677fffffffffffffff6107b0565b60405160200161037e929190610884565b604051602081830303815290604052906103ab5760405162461bcd60e51b81526004016101a0919061084f565b50335f908152602081905260409020548015156103c7826105af565b6040516020016103d791906108d3565b604051602081830303815290604052906104045760405162461bcd60e51b81526004016101a0919061084f565b505f6104156402540be4008461091e565b9050826104276402540be400836107b0565b14610431846105af565b61043f6402540be4006105af565b60405160200161045092919061093d565b6040516020818303038152906040529061047d5760405162461bcd60e51b81526004016101a0919061084f565b50435f8181526001602052604090205461ffff16610400908111906104a1906105af565b6040516020016104b1919061098c565b604051602081830303815290604052906104de5760405162461bcd60e51b81526004016101a0919061084f565b505f818152600160205260408120805461ffff16916104fc836109fa565b91906101000a81548161ffff021916908361ffff16021790555050835f80336001600160a01b03166001600160a01b031681526020019081526020015f205f8282546105489190610a1a565b9091555050604080516bffffffffffffffffffffffff1987168152600784900b60208201527f019ed605462769fdd9cdbf54b36e801c6ff41a3fb8d9bbe64974be03492956a7910160405180910390a15050505050565b6102ba6402540be40060016107b0565b6060815f036105d55750506040805180820190915260018152600360fc1b602082015290565b815f5b81156105fe57806105e881610a2d565b91506105f79050600a8361091e565b91506105d8565b5f8167ffffffffffffffff81111561061857610618610a45565b6040519080825280601f01601f191660200182016040528015610642576020820181803683370190505b509050815b85156106cc57610658600182610a1a565b90505f610666600a8861091e565b61067190600a6107b0565b61067b9088610a1a565b610686906030610a59565b90505f8160f81b9050808484815181106106a2576106a2610a72565b60200101906001600160f81b03191690815f1a9053506106c3600a8961091e565b97505050610647565b50949350505050565b80356001600160a01b03811681146106eb575f80fd5b919050565b5f60208284031215610700575f80fd5b610709826106d5565b9392505050565b5f60208284031215610720575f80fd5b5035919050565b5f8060408385031215610738575f80fd5b610741836106d5565b915060208301358060070b8114610756575f80fd5b809150509250929050565b5f8060408385031215610772575f80fd5b82356bffffffffffffffffffffffff198116811461078e575f80fd5b946020939093013593505050565b634e487b7160e01b5f52601160045260245ffd5b80820281158282048414176107c7576107c761079c565b92915050565b808201808211156107c7576107c761079c565b5f81518060208401855e5f93019283525090919050565b6a029b2b73a103b30b63ab2960ad1b81525f610816600b8301856107e0565b7f206d7573742062652067726561746572206f7220657175616c20746f200000008152610846601d8201856107e0565b95945050505050565b602081525f82518060208401528060208501604085015e5f604082850101526040601f19601f83011684010191505092915050565b6a029b2b73a103b30b63ab2960ad1b81525f6108a3600b8301856107e0565b7f206d757374206265206c657373206f7220657175616c20746f200000000000008152610846601a8201856107e0565b7f496e73756666696369656e742066756e64732c206f6e6c79200000000000000081525f61090460198301846107e0565b6920617661696c61626c6560b01b8152600a019392505050565b5f8261093857634e487b7160e01b5f52601260045260245ffd5b500490565b6a029b2b73a103b30b63ab2960ad1b81525f61095c600b8301856107e0565b7f206d7573742062652061206d756c7469706c65206f6620000000000000000000815261084660178201856107e0565b7f4d6178207472616e7366657273206c696d6974206f662000000000000000000081525f6109bd60178301846107e0565b7f207265616368656420696e207468697320626c6f636b2e20547279206167616981526637103630ba32b960c91b60208201526027019392505050565b5f61ffff821661ffff8103610a1157610a1161079c565b60010192915050565b818103818111156107c7576107c761079c565b5f60018201610a3e57610a3e61079c565b5060010190565b634e487b7160e01b5f52604160045260245ffd5b60ff81811683821601908111156107c7576107c761079c565b634e487b7160e01b5f52603260045260245ffdfea2646970667358221220a93724fe38c583d5661c718f7f9c88ff60338621cbeaff07644b0184b25f223264736f6c634300081a0033";
//
//    private static String librariesLinkedBinary;
//
//    public static final String FUNC_EL_TO_CL_RATIO = "EL_TO_CL_RATIO";
//
//    public static final String FUNC_MAX_AMOUNT_IN_WEI = "MAX_AMOUNT_IN_WEI";
//
//    public static final String FUNC_MAX_TRANSFERS_IN_BLOCK = "MAX_TRANSFERS_IN_BLOCK";
//
//    public static final String FUNC_MIN_AMOUNT_IN_WEI = "MIN_AMOUNT_IN_WEI";
//
//    public static final String FUNC_BALANCES = "balances";
//
//    public static final String FUNC_RECEIVEISSUED = "receiveIssued";
//
//    public static final String FUNC_SENDISSUED = "sendIssued";
//
//    public static final String FUNC_TRANSFERSPERBLOCK = "transfersPerBlock";
//
//    public static final Event RECEIVEDISSUED_EVENT = new Event("ReceivedIssued",
//            Arrays.<TypeReference<?>>asList(new TypeReference<Address>(true) {}, new TypeReference<Uint256>() {}));
//    ;
//
//    public static final Event SENTISSUED_EVENT = new Event("SentIssued",
//            Arrays.<TypeReference<?>>asList(new TypeReference<Bytes20>() {}, new TypeReference<Long>() {}));
//    ;
//
//    @Deprecated
//    protected BridgeUserContract(String contractAddress, Web3j web3j, Credentials credentials,
//            BigInteger gasPrice, BigInteger gasLimit) {
//        super(BINARY, contractAddress, web3j, credentials, gasPrice, gasLimit);
//    }
//
//    protected BridgeUserContract(String contractAddress, Web3j web3j, Credentials credentials,
//            ContractGasProvider contractGasProvider) {
//        super(BINARY, contractAddress, web3j, credentials, contractGasProvider);
//    }
//
//    @Deprecated
//    protected BridgeUserContract(String contractAddress, Web3j web3j,
//            TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
//        super(BINARY, contractAddress, web3j, transactionManager, gasPrice, gasLimit);
//    }
//
//    protected BridgeUserContract(String contractAddress, Web3j web3j,
//            TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
//        super(BINARY, contractAddress, web3j, transactionManager, contractGasProvider);
//    }
//
//    public RemoteFunctionCall<TransactionReceipt> send_receiveIssued(String receiver,
//            java.lang.Long amount) {
//        final Function function = new Function(
//                FUNC_RECEIVEISSUED,
//                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, receiver),
//                new org.web3j.abi.datatypes.primitive.Long(amount)),
//                Collections.<TypeReference<?>>emptyList());
//        return executeRemoteCallTransaction(function);
//    }
//
//    public RemoteFunctionCall<TransactionReceipt> send_sendIssued(byte[] wavesRecipient,
//            BigInteger amount) {
//        final Function function = new Function(
//                FUNC_SENDISSUED,
//                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Bytes20(wavesRecipient),
//                new org.web3j.abi.datatypes.generated.Uint256(amount)),
//                Collections.<TypeReference<?>>emptyList());
//        return executeRemoteCallTransaction(function);
//    }
//
//    public static class ReceivedIssuedEventResponse extends BaseEventResponse {
//        public String receiver;
//
//        public BigInteger amount;
//    }
//
//    public static class SentIssuedEventResponse extends BaseEventResponse {
//        public byte[] wavesRecipient;
//
//        public java.lang.Long amount;
//    }
//}
