package units.bridge;

import io.reactivex.Flowable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
public class IssuedTokenBridgeContract extends Contract {
    public static final String BINARY = "6080604052348015600e575f80fd5b50610b048061001c5f395ff3fe608060405234801561000f575f80fd5b5060043610610090575f3560e01c80637157405a116100635780637157405a1461012457806396f396c31461012d578063c4a4326d14610135578063e439d4cd14610141578063e984df0e14610154575f80fd5b806327e235e314610094578063380d336a146100c657806339dd5d1b146100db57806340c10f1914610111575b5f80fd5b6100b36100a236600461071e565b5f6020819052908152604090205481565b6040519081526020015b60405180910390f35b6100d96100d436600461073e565b61015c565b005b6100fe6100e9366004610778565b60016020525f908152604090205461ffff1681565b60405161ffff90911681526020016100bd565b6100d961011f36600461078f565b6102a6565b6100fe61040081565b6100b36102d6565b6100b36402540be40081565b6100d961014f3660046107b7565b6102f0565b6100b36105a6565b5f8160070b136101c75760405162461bcd60e51b815260206004820152602b60248201527f526563656976652076616c7565206d7573742062652067726561746572206f7260448201526a020657175616c20746f20360ac1b60648201526084015b60405180910390fd5b5f6101db6402540be400600784900b6107f8565b90506101f46402540be400677fffffffffffffff6107f8565b8111156102525760405162461bcd60e51b815260206004820152602660248201527f416d6f756e742065786365656473206d6178696d756d20616c6c6f7761626c656044820152652076616c756560d01b60648201526084016101be565b61025c83826102a6565b604080516001600160a01b0385168152600784900b60208201527feb3c36ef9a5b601d174ff53d4e6efd175aa09b9d9547e32cc09580e7cd34eadd910160405180910390a1505050565b6001600160a01b0382165f90815260208190526040812080548392906102cd908490610815565b90915550505050565b6102ed6402540be400677fffffffffffffff6107f8565b81565b6103006402540be40060016107f8565b81101561030c826105b6565b61032461031f6402540be40060016107f8565b6105b6565b60405160200161033592919061083f565b604051602081830303815290604052906103625760405162461bcd60e51b81526004016101be9190610897565b5061037a6402540be400677fffffffffffffff6107f8565b811115610386826105b6565b6103a061031f6402540be400677fffffffffffffff6107f8565b6040516020016103b19291906108cc565b604051602081830303815290604052906103de5760405162461bcd60e51b81526004016101be9190610897565b50335f908152602081905260409020548015156103fa826105b6565b60405160200161040a919061091b565b604051602081830303815290604052906104375760405162461bcd60e51b81526004016101be9190610897565b505f6104486402540be40084610966565b90508261045a6402540be400836107f8565b14610464846105b6565b6104726402540be4006105b6565b604051602001610483929190610985565b604051602081830303815290604052906104b05760405162461bcd60e51b81526004016101be9190610897565b50435f8181526001602052604090205461ffff16610400908111906104d4906105b6565b6040516020016104e491906109d4565b604051602081830303815290604052906105115760405162461bcd60e51b81526004016101be9190610897565b505f818152600160205260408120805461ffff169161052f83610a42565b91906101000a81548161ffff021916908361ffff1602179055505061055433856106dc565b604080516bffffffffffffffffffffffff1987168152600784900b60208201527f97e5059b52a3213bb884e3f81ef3652170d4d52965abdf8d970b8612026102dd910160405180910390a15050505050565b6102ed6402540be40060016107f8565b6060815f036105dc5750506040805180820190915260018152600360fc1b602082015290565b815f5b811561060557806105ef81610a62565b91506105fe9050600a83610966565b91506105df565b5f8167ffffffffffffffff81111561061f5761061f610a7a565b6040519080825280601f01601f191660200182016040528015610649576020820181803683370190505b509050815b85156106d35761065f600182610a8e565b90505f61066d600a88610966565b61067890600a6107f8565b6106829088610a8e565b61068d906030610aa1565b90505f8160f81b9050808484815181106106a9576106a9610aba565b60200101906001600160f81b03191690815f1a9053506106ca600a89610966565b9750505061064e565b50949350505050565b6001600160a01b0382165f90815260208190526040812080548392906102cd908490610a8e565b80356001600160a01b0381168114610719575f80fd5b919050565b5f6020828403121561072e575f80fd5b61073782610703565b9392505050565b5f806040838503121561074f575f80fd5b61075883610703565b915060208301358060070b811461076d575f80fd5b809150509250929050565b5f60208284031215610788575f80fd5b5035919050565b5f80604083850312156107a0575f80fd5b6107a983610703565b946020939093013593505050565b5f80604083850312156107c8575f80fd5b82356bffffffffffffffffffffffff19811681146107a9575f80fd5b634e487b7160e01b5f52601160045260245ffd5b808202811582820484141761080f5761080f6107e4565b92915050565b8082018082111561080f5761080f6107e4565b5f81518060208401855e5f93019283525090919050565b6a029b2b73a103b30b63ab2960ad1b81525f61085e600b830185610828565b7f206d7573742062652067726561746572206f7220657175616c20746f20000000815261088e601d820185610828565b95945050505050565b602081525f82518060208401528060208501604085015e5f604082850101526040601f19601f83011684010191505092915050565b6a029b2b73a103b30b63ab2960ad1b81525f6108eb600b830185610828565b7f206d757374206265206c657373206f7220657175616c20746f20000000000000815261088e601a820185610828565b7f496e73756666696369656e742066756e64732c206f6e6c79200000000000000081525f61094c6019830184610828565b6920617661696c61626c6560b01b8152600a019392505050565b5f8261098057634e487b7160e01b5f52601260045260245ffd5b500490565b6a029b2b73a103b30b63ab2960ad1b81525f6109a4600b830185610828565b7f206d7573742062652061206d756c7469706c65206f6620000000000000000000815261088e6017820185610828565b7f4d6178207472616e7366657273206c696d6974206f662000000000000000000081525f610a056017830184610828565b7f207265616368656420696e207468697320626c6f636b2e20547279206167616981526637103630ba32b960c91b60208201526027019392505050565b5f61ffff821661ffff8103610a5957610a596107e4565b60010192915050565b5f60018201610a7357610a736107e4565b5060010190565b634e487b7160e01b5f52604160045260245ffd5b8181038181111561080f5761080f6107e4565b60ff818116838216019081111561080f5761080f6107e4565b634e487b7160e01b5f52603260045260245ffdfea26469706673582212207dbe4a6a4fcd58b6c2bafbdca02f5dc0267e05400ce0431c2fd28c622177662064736f6c634300081a0033";

    private static String librariesLinkedBinary;

    public static final String FUNC_EL_TO_CL_RATIO = "EL_TO_CL_RATIO";

    public static final String FUNC_MAX_AMOUNT_IN_WEI = "MAX_AMOUNT_IN_WEI";

    public static final String FUNC_MAX_TRANSFERS_IN_BLOCK = "MAX_TRANSFERS_IN_BLOCK";

    public static final String FUNC_MIN_AMOUNT_IN_WEI = "MIN_AMOUNT_IN_WEI";

    public static final String FUNC_BALANCES = "balances";

    public static final String FUNC_BRIDGEERC20 = "bridgeERC20";

    public static final String FUNC_FINALIZEBRIDGEERC20 = "finalizeBridgeERC20";

    public static final String FUNC_MINT = "mint";

    public static final String FUNC_TRANSFERSPERBLOCK = "transfersPerBlock";

    public static final Event ERC20BRIDGEFINALIZED_EVENT = new Event("ERC20BridgeFinalized", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {}, new TypeReference<Long>() {}));
    ;

    public static final Event ERC20BRIDGEINITIATED_EVENT = new Event("ERC20BridgeInitiated", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Bytes20>() {}, new TypeReference<Long>() {}));
    ;

    @Deprecated
    protected IssuedTokenBridgeContract(String contractAddress, Web3j web3j,
            Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    protected IssuedTokenBridgeContract(String contractAddress, Web3j web3j,
            Credentials credentials, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, credentials, contractGasProvider);
    }

    @Deprecated
    protected IssuedTokenBridgeContract(String contractAddress, Web3j web3j,
            TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    protected IssuedTokenBridgeContract(String contractAddress, Web3j web3j,
            TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public static List<ERC20BridgeFinalizedEventResponse> getERC20BridgeFinalizedEvents(
            TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(ERC20BRIDGEFINALIZED_EVENT, transactionReceipt);
        ArrayList<ERC20BridgeFinalizedEventResponse> responses = new ArrayList<ERC20BridgeFinalizedEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            ERC20BridgeFinalizedEventResponse typedResponse = new ERC20BridgeFinalizedEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.recipient = (String) eventValues.getNonIndexedValues().get(0).getValue();
            typedResponse.clAmount = (java.lang.Long) eventValues.getNonIndexedValues().get(1).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static ERC20BridgeFinalizedEventResponse getERC20BridgeFinalizedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(ERC20BRIDGEFINALIZED_EVENT, log);
        ERC20BridgeFinalizedEventResponse typedResponse = new ERC20BridgeFinalizedEventResponse();
        typedResponse.log = log;
        typedResponse.recipient = (String) eventValues.getNonIndexedValues().get(0).getValue();
        typedResponse.clAmount = (java.lang.Long) eventValues.getNonIndexedValues().get(1).getValue();
        return typedResponse;
    }

    public Flowable<ERC20BridgeFinalizedEventResponse> eRC20BridgeFinalizedEventFlowable(
            EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getERC20BridgeFinalizedEventFromLog(log));
    }

    public Flowable<ERC20BridgeFinalizedEventResponse> eRC20BridgeFinalizedEventFlowable(
            DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(ERC20BRIDGEFINALIZED_EVENT));
        return eRC20BridgeFinalizedEventFlowable(filter);
    }

    public static List<ERC20BridgeInitiatedEventResponse> getERC20BridgeInitiatedEvents(
            TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(ERC20BRIDGEINITIATED_EVENT, transactionReceipt);
        ArrayList<ERC20BridgeInitiatedEventResponse> responses = new ArrayList<ERC20BridgeInitiatedEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            ERC20BridgeInitiatedEventResponse typedResponse = new ERC20BridgeInitiatedEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.wavesRecipient = (byte[]) eventValues.getNonIndexedValues().get(0).getValue();
            typedResponse.clAmount = (java.lang.Long) eventValues.getNonIndexedValues().get(1).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static ERC20BridgeInitiatedEventResponse getERC20BridgeInitiatedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(ERC20BRIDGEINITIATED_EVENT, log);
        ERC20BridgeInitiatedEventResponse typedResponse = new ERC20BridgeInitiatedEventResponse();
        typedResponse.log = log;
        typedResponse.wavesRecipient = (byte[]) eventValues.getNonIndexedValues().get(0).getValue();
        typedResponse.clAmount = (java.lang.Long) eventValues.getNonIndexedValues().get(1).getValue();
        return typedResponse;
    }

    public Flowable<ERC20BridgeInitiatedEventResponse> eRC20BridgeInitiatedEventFlowable(
            EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getERC20BridgeInitiatedEventFromLog(log));
    }

    public Flowable<ERC20BridgeInitiatedEventResponse> eRC20BridgeInitiatedEventFlowable(
            DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(ERC20BRIDGEINITIATED_EVENT));
        return eRC20BridgeInitiatedEventFlowable(filter);
    }

    public RemoteFunctionCall<BigInteger> call_EL_TO_CL_RATIO() {
        final Function function = new Function(FUNC_EL_TO_CL_RATIO, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
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
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
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
                Arrays.<TypeReference<?>>asList(new TypeReference<Int>() {}));
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
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_MIN_AMOUNT_IN_WEI() {
        final Function function = new Function(
                FUNC_MIN_AMOUNT_IN_WEI, 
                Arrays.<Type>asList(), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<BigInteger> call_balances(String param0) {
        final Function function = new Function(FUNC_BALANCES, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_balances(String param0) {
        final Function function = new Function(
                FUNC_BALANCES, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> send_bridgeERC20(byte[] wavesRecipient,
            BigInteger elAmount) {
        final Function function = new Function(
                FUNC_BRIDGEERC20, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Bytes20(wavesRecipient), 
                new org.web3j.abi.datatypes.generated.Uint256(elAmount)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> send_finalizeBridgeERC20(String recipient,
            java.lang.Long clAmount) {
        final Function function = new Function(
                FUNC_FINALIZEBRIDGEERC20, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, recipient), 
                new org.web3j.abi.datatypes.primitive.Long(clAmount)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> send_mint(String recipient, BigInteger elAmount) {
        final Function function = new Function(
                FUNC_MINT, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, recipient), 
                new org.web3j.abi.datatypes.generated.Uint256(elAmount)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<Integer> call_transfersPerBlock(BigInteger param0) {
        final Function function = new Function(FUNC_TRANSFERSPERBLOCK, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Uint256(param0)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Int>() {}));
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
    public static IssuedTokenBridgeContract load(String contractAddress, Web3j web3j,
            Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        return new IssuedTokenBridgeContract(contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    @Deprecated
    public static IssuedTokenBridgeContract load(String contractAddress, Web3j web3j,
            TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return new IssuedTokenBridgeContract(contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    public static IssuedTokenBridgeContract load(String contractAddress, Web3j web3j,
            Credentials credentials, ContractGasProvider contractGasProvider) {
        return new IssuedTokenBridgeContract(contractAddress, web3j, credentials, contractGasProvider);
    }

    public static IssuedTokenBridgeContract load(String contractAddress, Web3j web3j,
            TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return new IssuedTokenBridgeContract(contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public static RemoteCall<IssuedTokenBridgeContract> deploy(Web3j web3j, Credentials credentials,
            ContractGasProvider contractGasProvider) {
        return deployRemoteCall(IssuedTokenBridgeContract.class, web3j, credentials, contractGasProvider, getDeploymentBinary(), "");
    }

    @Deprecated
    public static RemoteCall<IssuedTokenBridgeContract> deploy(Web3j web3j, Credentials credentials,
            BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(IssuedTokenBridgeContract.class, web3j, credentials, gasPrice, gasLimit, getDeploymentBinary(), "");
    }

    public static RemoteCall<IssuedTokenBridgeContract> deploy(Web3j web3j,
            TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(IssuedTokenBridgeContract.class, web3j, transactionManager, contractGasProvider, getDeploymentBinary(), "");
    }

    @Deprecated
    public static RemoteCall<IssuedTokenBridgeContract> deploy(Web3j web3j,
            TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(IssuedTokenBridgeContract.class, web3j, transactionManager, gasPrice, gasLimit, getDeploymentBinary(), "");
    }

    private static String getDeploymentBinary() {
        if (librariesLinkedBinary != null) {
            return librariesLinkedBinary;
        } else {
            return BINARY;
        }
    }

    public static class ERC20BridgeFinalizedEventResponse extends BaseEventResponse {
        public String recipient;

        public java.lang.Long clAmount;
    }

    public static class ERC20BridgeInitiatedEventResponse extends BaseEventResponse {
        public byte[] wavesRecipient;

        public java.lang.Long clAmount;
    }
}
