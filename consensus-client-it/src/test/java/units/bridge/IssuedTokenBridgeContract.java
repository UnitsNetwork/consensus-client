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
    public static final String BINARY = "6080604052348015600e575f80fd5b50610b108061001c5f395ff3fe608060405234801561000f575f80fd5b5060043610610090575f3560e01c80637157405a116100635780637157405a1461012457806396f396c31461012d578063c4a4326d14610135578063e439d4cd14610141578063e984df0e14610154575f80fd5b806327e235e314610094578063380d336a146100c657806339dd5d1b146100db57806340c10f1914610111575b5f80fd5b6100b36100a236600461072a565b5f6020819052908152604090205481565b6040519081526020015b60405180910390f35b6100d96100d436600461074a565b61015c565b005b6100fe6100e9366004610784565b60016020525f908152604090205461ffff1681565b60405161ffff90911681526020016100bd565b6100d961011f36600461079b565b6102ac565b6100fe61040081565b6100b36102dc565b6100b36402540be40081565b6100d961014f3660046107c3565b6102f6565b6100b36105b2565b5f8160070b136101c75760405162461bcd60e51b815260206004820152602b60248201527f526563656976652076616c7565206d7573742062652067726561746572206f7260448201526a020657175616c20746f20360ac1b60648201526084015b60405180910390fd5b5f6101db6402540be400600784900b610804565b90506101f46402540be400677fffffffffffffff610804565b8111156102525760405162461bcd60e51b815260206004820152602660248201527f416d6f756e742065786365656473206d6178696d756d20616c6c6f7761626c656044820152652076616c756560d01b60648201526084016101be565b61025c83826102ac565b604080516001600160a01b0385168152600784900b6020820152308183015290517f79ba61b7b220c73204f9deefe02364b84339a67c836feb371b0b7c9860084d5e9181900360600190a1505050565b6001600160a01b0382165f90815260208190526040812080548392906102d3908490610821565b90915550505050565b6102f36402540be400677fffffffffffffff610804565b81565b6103066402540be4006001610804565b811015610312826105c2565b61032a6103256402540be4006001610804565b6105c2565b60405160200161033b92919061084b565b604051602081830303815290604052906103685760405162461bcd60e51b81526004016101be91906108a3565b506103806402540be400677fffffffffffffff610804565b81111561038c826105c2565b6103a66103256402540be400677fffffffffffffff610804565b6040516020016103b79291906108d8565b604051602081830303815290604052906103e45760405162461bcd60e51b81526004016101be91906108a3565b50335f90815260208190526040902054801515610400826105c2565b6040516020016104109190610927565b6040516020818303038152906040529061043d5760405162461bcd60e51b81526004016101be91906108a3565b505f61044e6402540be40084610972565b9050826104606402540be40083610804565b1461046a846105c2565b6104786402540be4006105c2565b604051602001610489929190610991565b604051602081830303815290604052906104b65760405162461bcd60e51b81526004016101be91906108a3565b50435f8181526001602052604090205461ffff16610400908111906104da906105c2565b6040516020016104ea91906109e0565b604051602081830303815290604052906105175760405162461bcd60e51b81526004016101be91906108a3565b505f818152600160205260408120805461ffff169161053583610a4e565b91906101000a81548161ffff021916908361ffff1602179055505061055a33856106e8565b604080516bffffffffffffffffffffffff1987168152600784900b6020820152308183015290517fdc193e59112938988cb57c4abdeb47ac3889408ba6f39d7061bee9476907bedc9181900360600190a15050505050565b6102f36402540be4006001610804565b6060815f036105e85750506040805180820190915260018152600360fc1b602082015290565b815f5b811561061157806105fb81610a6e565b915061060a9050600a83610972565b91506105eb565b5f8167ffffffffffffffff81111561062b5761062b610a86565b6040519080825280601f01601f191660200182016040528015610655576020820181803683370190505b509050815b85156106df5761066b600182610a9a565b90505f610679600a88610972565b61068490600a610804565b61068e9088610a9a565b610699906030610aad565b90505f8160f81b9050808484815181106106b5576106b5610ac6565b60200101906001600160f81b03191690815f1a9053506106d6600a89610972565b9750505061065a565b50949350505050565b6001600160a01b0382165f90815260208190526040812080548392906102d3908490610a9a565b80356001600160a01b0381168114610725575f80fd5b919050565b5f6020828403121561073a575f80fd5b6107438261070f565b9392505050565b5f806040838503121561075b575f80fd5b6107648361070f565b915060208301358060070b8114610779575f80fd5b809150509250929050565b5f60208284031215610794575f80fd5b5035919050565b5f80604083850312156107ac575f80fd5b6107b58361070f565b946020939093013593505050565b5f80604083850312156107d4575f80fd5b82356bffffffffffffffffffffffff19811681146107b5575f80fd5b634e487b7160e01b5f52601160045260245ffd5b808202811582820484141761081b5761081b6107f0565b92915050565b8082018082111561081b5761081b6107f0565b5f81518060208401855e5f93019283525090919050565b6a029b2b73a103b30b63ab2960ad1b81525f61086a600b830185610834565b7f206d7573742062652067726561746572206f7220657175616c20746f20000000815261089a601d820185610834565b95945050505050565b602081525f82518060208401528060208501604085015e5f604082850101526040601f19601f83011684010191505092915050565b6a029b2b73a103b30b63ab2960ad1b81525f6108f7600b830185610834565b7f206d757374206265206c657373206f7220657175616c20746f20000000000000815261089a601a820185610834565b7f496e73756666696369656e742066756e64732c206f6e6c79200000000000000081525f6109586019830184610834565b6920617661696c61626c6560b01b8152600a019392505050565b5f8261098c57634e487b7160e01b5f52601260045260245ffd5b500490565b6a029b2b73a103b30b63ab2960ad1b81525f6109b0600b830185610834565b7f206d7573742062652061206d756c7469706c65206f6620000000000000000000815261089a6017820185610834565b7f4d6178207472616e7366657273206c696d6974206f662000000000000000000081525f610a116017830184610834565b7f207265616368656420696e207468697320626c6f636b2e20547279206167616981526637103630ba32b960c91b60208201526027019392505050565b5f61ffff821661ffff8103610a6557610a656107f0565b60010192915050565b5f60018201610a7f57610a7f6107f0565b5060010190565b634e487b7160e01b5f52604160045260245ffd5b8181038181111561081b5761081b6107f0565b60ff818116838216019081111561081b5761081b6107f0565b634e487b7160e01b5f52603260045260245ffdfea2646970667358221220896c7f88353bd4aba5c30f66d769d2ca6e202cc8860f7771f1c72c7192cbdd5864736f6c634300081a0033";

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
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {}, new TypeReference<Long>() {}, new TypeReference<Address>() {}));
    ;

    public static final Event ERC20BRIDGEINITIATED_EVENT = new Event("ERC20BridgeInitiated", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Bytes20>() {}, new TypeReference<Long>() {}, new TypeReference<Address>() {}));
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
            typedResponse.assetId = (String) eventValues.getNonIndexedValues().get(2).getValue();
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
        typedResponse.assetId = (String) eventValues.getNonIndexedValues().get(2).getValue();
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
            typedResponse.assetId = (String) eventValues.getNonIndexedValues().get(2).getValue();
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
        typedResponse.assetId = (String) eventValues.getNonIndexedValues().get(2).getValue();
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

        public String assetId;
    }

    public static class ERC20BridgeInitiatedEventResponse extends BaseEventResponse {
        public byte[] wavesRecipient;

        public java.lang.Long clAmount;

        public String assetId;
    }
}
