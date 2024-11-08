package units.bridge;

import io.reactivex.Flowable;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes20;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.primitive.Int;
import org.web3j.abi.datatypes.primitive.Long;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.BaseEventResponse;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.Contract;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * <p>Auto generated code.
 * <p><strong>Do not modify!</strong>
 * <p>Please use the <a href="https://docs.web3j.io/command_line.html">web3j command line tools</a>,
 * or the org.web3j.codegen.SolidityFunctionWrapperGenerator in the
 * <a href="https://github.com/hyperledger/web3j/tree/main/codegen">codegen module</a> to update.
 *
 * <p>Generated with web3j version 1.6.1.
 */
@SuppressWarnings("rawtypes")
public class BridgeContract extends Contract {
    public static final String BINARY = "6080604052348015600e575f80fd5b506108b68061001c5f395ff3fe60806040526004361061006e575f3560e01c806396f396c31161004c57806396f396c3146100e3578063c4a4326d14610105578063e984df0e1461011d578063fccc281314610131575f80fd5b806339dd5d1b146100725780637157405a146100b957806378338413146100ce575b5f80fd5b34801561007d575f80fd5b506100a161008c36600461059e565b5f6020819052908152604090205461ffff1681565b60405161ffff90911681526020015b60405180910390f35b3480156100c4575f80fd5b506100a161040081565b6100e16100dc3660046105b5565b61015c565b005b3480156100ee575f80fd5b506100f761044e565b6040519081526020016100b0565b348015610110575f80fd5b506100f76402540be40081565b348015610128575f80fd5b506100f7610468565b34801561013c575f80fd5b506101445f81565b6040516001600160a01b0390911681526020016100b0565b61016c6402540be40060016105fc565b34101561017834610478565b61019061018b6402540be40060016105fc565b610478565b6040516020016101a1929190610630565b604051602081830303815290604052906101d75760405162461bcd60e51b81526004016101ce9190610688565b60405180910390fd5b506101ef6402540be400677fffffffffffffff6105fc565b3411156101fb34610478565b61021561018b6402540be400677fffffffffffffff6105fc565b6040516020016102269291906106bd565b604051602081830303815290604052906102535760405162461bcd60e51b81526004016101ce9190610688565b50435f8181526020819052604090205461ffff166104009081119061027790610478565b604051602001610287919061070c565b604051602081830303815290604052906102b45760405162461bcd60e51b81526004016101ce9190610688565b505f818152602081905260408120805461ffff16916102d283610786565b91906101000a81548161ffff021916908361ffff160217905550505f6402540be400346102ff91906107a6565b9050346103116402540be400836105fc565b1461031b34610478565b6103296402540be400610478565b60405160200161033a9291906107c5565b604051602081830303815290604052906103675760405162461bcd60e51b81526004016101ce9190610688565b506040515f90819034908281818185825af1925050503d805f81146103a7576040519150601f19603f3d011682016040523d82523d5f602084013e6103ac565b606091505b50509050806103fd5760405162461bcd60e51b815260206004820152601e60248201527f4661696c656420746f2073656e6420746f206275726e2061646472657373000060448201526064016101ce565b604080516bffffffffffffffffffffffff1986168152600784900b60208201527ffeadaf04de8d7c2594453835b9a93b747e20e7a09a7fdb9280579a6dbaf131a8910160405180910390a150505050565b6104656402540be400677fffffffffffffff6105fc565b81565b6104656402540be40060016105fc565b6060815f0361049e5750506040805180820190915260018152600360fc1b602082015290565b815f5b81156104c757806104b181610814565b91506104c09050600a836107a6565b91506104a1565b5f8167ffffffffffffffff8111156104e1576104e161082c565b6040519080825280601f01601f19166020018201604052801561050b576020820181803683370190505b509050815b851561059557610521600182610840565b90505f61052f600a886107a6565b61053a90600a6105fc565b6105449088610840565b61054f906030610853565b90505f8160f81b90508084848151811061056b5761056b61086c565b60200101906001600160f81b03191690815f1a90535061058c600a896107a6565b97505050610510565b50949350505050565b5f602082840312156105ae575f80fd5b5035919050565b5f602082840312156105c5575f80fd5b81356bffffffffffffffffffffffff19811681146105e1575f80fd5b9392505050565b634e487b7160e01b5f52601160045260245ffd5b8082028115828204841417610613576106136105e8565b92915050565b5f81518060208401855e5f93019283525090919050565b6a029b2b73a103b30b63ab2960ad1b81525f61064f600b830185610619565b7f206d7573742062652067726561746572206f7220657175616c20746f20000000815261067f601d820185610619565b95945050505050565b602081525f82518060208401528060208501604085015e5f604082850101526040601f19601f83011684010191505092915050565b6a029b2b73a103b30b63ab2960ad1b81525f6106dc600b830185610619565b7f206d757374206265206c657373206f7220657175616c20746f20000000000000815261067f601a820185610619565b7f4d6178207472616e7366657273206c696d6974206f662000000000000000000081525f61073d6017830184610619565b7f207265616368656420696e207468697320626c6f636b2e2054727920746f207381527232b732103a3930b739b332b9399030b3b0b4b760691b60208201526033019392505050565b5f61ffff821661ffff810361079d5761079d6105e8565b60010192915050565b5f826107c057634e487b7160e01b5f52601260045260245ffd5b500490565b6a029b2b73a103b30b63ab2960ad1b81525f6107e4600b830185610619565b7f206d7573742062652061206d756c7469706c65206f6620000000000000000000815261067f6017820185610619565b5f60018201610825576108256105e8565b5060010190565b634e487b7160e01b5f52604160045260245ffd5b81810381811115610613576106136105e8565b60ff8181168382160190811115610613576106136105e8565b634e487b7160e01b5f52603260045260245ffdfea2646970667358221220106399f534da089226c14e2f183f8421d059a924c65c97d7e4f3e931c54fe1bb64736f6c634300081a0033";

    private static String librariesLinkedBinary;

    public static final String FUNC_BURN_ADDRESS = "BURN_ADDRESS";

    public static final String FUNC_EL_TO_CL_RATIO = "EL_TO_CL_RATIO";

    public static final String FUNC_MAX_AMOUNT_IN_WEI = "MAX_AMOUNT_IN_WEI";

    public static final String FUNC_MAX_TRANSFERS_IN_BLOCK = "MAX_TRANSFERS_IN_BLOCK";

    public static final String FUNC_MIN_AMOUNT_IN_WEI = "MIN_AMOUNT_IN_WEI";

    public static final String FUNC_SENDNATIVE = "sendNative";

    public static final String FUNC_TRANSFERSPERBLOCK = "transfersPerBlock";

    public static final Event SENTNATIVE_EVENT = new Event("SentNative",
            Arrays.<TypeReference<?>>asList(new TypeReference<Bytes20>() {
            }, new TypeReference<Long>() {
            }));
    ;

    @Deprecated
    protected BridgeContract(String contractAddress, Web3j web3j, Credentials credentials,
                             BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    protected BridgeContract(String contractAddress, Web3j web3j, Credentials credentials,
                             ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, credentials, contractGasProvider);
    }

    @Deprecated
    protected BridgeContract(String contractAddress, Web3j web3j,
                             TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    protected BridgeContract(String contractAddress, Web3j web3j,
                             TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public static List<SentNativeEventResponse> getSentNativeEvents(
            TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(SENTNATIVE_EVENT, transactionReceipt);
        ArrayList<SentNativeEventResponse> responses = new ArrayList<SentNativeEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            SentNativeEventResponse typedResponse = new SentNativeEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.wavesRecipient = (byte[]) eventValues.getNonIndexedValues().get(0).getValue();
            typedResponse.amount = (java.lang.Long) eventValues.getNonIndexedValues().get(1).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static SentNativeEventResponse getSentNativeEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(SENTNATIVE_EVENT, log);
        SentNativeEventResponse typedResponse = new SentNativeEventResponse();
        typedResponse.log = log;
        typedResponse.wavesRecipient = (byte[]) eventValues.getNonIndexedValues().get(0).getValue();
        typedResponse.amount = (java.lang.Long) eventValues.getNonIndexedValues().get(1).getValue();
        return typedResponse;
    }

    public Flowable<SentNativeEventResponse> sentNativeEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getSentNativeEventFromLog(log));
    }

    public Flowable<SentNativeEventResponse> sentNativeEventFlowable(
            DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(SENTNATIVE_EVENT));
        return sentNativeEventFlowable(filter);
    }

    public RemoteFunctionCall<String> call_BURN_ADDRESS() {
        final Function function = new Function(FUNC_BURN_ADDRESS,
                Arrays.<Type>asList(),
                Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {
                }));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_BURN_ADDRESS() {
        final Function function = new Function(
                FUNC_BURN_ADDRESS,
                Arrays.<Type>asList(),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<BigInteger> call_EL_TO_CL_RATIO() {
        final Function function = new Function(FUNC_EL_TO_CL_RATIO,
                Arrays.<Type>asList(),
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {
                }));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_EL_TO_CL_RATIO() {
        final Function function = new Function(
                FUNC_EL_TO_CL_RATIO,
                Arrays.<Type>asList(),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<BigInteger> call_MAX_AMOUNT_IN_WEI() {
        final Function function = new Function(FUNC_MAX_AMOUNT_IN_WEI,
                Arrays.<Type>asList(),
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {
                }));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_MAX_AMOUNT_IN_WEI() {
        final Function function = new Function(
                FUNC_MAX_AMOUNT_IN_WEI,
                Arrays.<Type>asList(),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<Integer> call_MAX_TRANSFERS_IN_BLOCK() {
        final Function function = new Function(FUNC_MAX_TRANSFERS_IN_BLOCK,
                Arrays.<Type>asList(),
                Arrays.<TypeReference<?>>asList(new TypeReference<Int>() {
                }));
        return executeRemoteCallSingleValueReturn(function, Integer.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_MAX_TRANSFERS_IN_BLOCK() {
        final Function function = new Function(
                FUNC_MAX_TRANSFERS_IN_BLOCK,
                Arrays.<Type>asList(),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<BigInteger> call_MIN_AMOUNT_IN_WEI() {
        final Function function = new Function(FUNC_MIN_AMOUNT_IN_WEI,
                Arrays.<Type>asList(),
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {
                }));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_MIN_AMOUNT_IN_WEI() {
        final Function function = new Function(
                FUNC_MIN_AMOUNT_IN_WEI,
                Arrays.<Type>asList(),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> send_sendNative(byte[] wavesRecipient,
                                                                  BigInteger weiValue) {
        final Function function = new Function(
                FUNC_SENDNATIVE,
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Bytes20(wavesRecipient)),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function, weiValue);
    }

    public RemoteFunctionCall<Integer> call_transfersPerBlock(BigInteger param0) {
        final Function function = new Function(FUNC_TRANSFERSPERBLOCK,
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Uint256(param0)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Int>() {
                }));
        return executeRemoteCallSingleValueReturn(function, Integer.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_transfersPerBlock(BigInteger param0) {
        final Function function = new Function(
                FUNC_TRANSFERSPERBLOCK,
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Uint256(param0)),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    @Deprecated
    public static BridgeContract load(String contractAddress, Web3j web3j, Credentials credentials,
                                      BigInteger gasPrice, BigInteger gasLimit) {
        return new BridgeContract(contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    @Deprecated
    public static BridgeContract load(String contractAddress, Web3j web3j,
                                      TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return new BridgeContract(contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    public static BridgeContract load(String contractAddress, Web3j web3j, Credentials credentials,
                                      ContractGasProvider contractGasProvider) {
        return new BridgeContract(contractAddress, web3j, credentials, contractGasProvider);
    }

    public static BridgeContract load(String contractAddress, Web3j web3j,
                                      TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return new BridgeContract(contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public static RemoteCall<BridgeContract> deploy(Web3j web3j, Credentials credentials,
                                                    ContractGasProvider contractGasProvider) {
        return deployRemoteCall(BridgeContract.class, web3j, credentials, contractGasProvider, getDeploymentBinary(), "");
    }

    @Deprecated
    public static RemoteCall<BridgeContract> deploy(Web3j web3j, Credentials credentials,
                                                    BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(BridgeContract.class, web3j, credentials, gasPrice, gasLimit, getDeploymentBinary(), "");
    }

    public static RemoteCall<BridgeContract> deploy(Web3j web3j,
                                                    TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(BridgeContract.class, web3j, transactionManager, contractGasProvider, getDeploymentBinary(), "");
    }

    @Deprecated
    public static RemoteCall<BridgeContract> deploy(Web3j web3j,
                                                    TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(BridgeContract.class, web3j, transactionManager, gasPrice, gasLimit, getDeploymentBinary(), "");
    }

    private static String getDeploymentBinary() {
        if (librariesLinkedBinary != null) {
            return librariesLinkedBinary;
        } else {
            return BINARY;
        }
    }

    public static class SentNativeEventResponse extends BaseEventResponse {
        public byte[] wavesRecipient;

        public java.lang.Long amount;
    }
}
