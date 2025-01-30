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
import org.web3j.abi.datatypes.Array;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
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
    public static final String BINARY = "6080604052348015600e575f80fd5b50610d7b8061001c5f395ff3fe608060405234801561000f575f80fd5b50600436106100a6575f3560e01c806396f396c31161006e57806396f396c314610143578063c4a4326d1461014b578063e439d4cd14610157578063e984df0e1461016a578063ee142d1414610172578063fdd0751714610185575f80fd5b806327e235e3146100aa578063380d336a146100dc57806339dd5d1b146100f157806340c10f19146101275780637157405a1461013a575b5f80fd5b6100c96100b8366004610894565b5f6020819052908152604090205481565b6040519081526020015b60405180910390f35b6100ef6100ea3660046108b4565b6101b7565b005b6101146100ff3660046108ee565b60016020525f908152604090205461ffff1681565b60405161ffff90911681526020016100d3565b6100ef610135366004610905565b610307565b61011461040081565b6100c9610337565b6100c96402540be40081565b6100ef61016536600461092d565b610351565b6100c961066b565b6100ef61018036600461095a565b61067b565b6101a7610193366004610894565b60026020525f908152604090205460ff1681565b60405190151581526020016100d3565b5f8160070b136102225760405162461bcd60e51b815260206004820152602b60248201527f526563656976652076616c7565206d7573742062652067726561746572206f7260448201526a020657175616c20746f20360ac1b60648201526084015b60405180910390fd5b5f6102366402540be400600784900b6109df565b905061024f6402540be400677fffffffffffffff6109df565b8111156102ad5760405162461bcd60e51b815260206004820152602660248201527f416d6f756e742065786365656473206d6178696d756d20616c6c6f7761626c656044820152652076616c756560d01b6064820152608401610219565b6102b78382610307565b604080516001600160a01b0385168152600784900b6020820152308183015290517f79ba61b7b220c73204f9deefe02364b84339a67c836feb371b0b7c9860084d5e9181900360600190a1505050565b6001600160a01b0382165f908152602081905260408120805483929061032e9084906109fc565b90915550505050565b61034e6402540be400677fffffffffffffff6109df565b81565b6103616402540be40060016109df565b81101561036d8261072c565b6103856103806402540be40060016109df565b61072c565b604051602001610396929190610a26565b604051602081830303815290604052906103c35760405162461bcd60e51b81526004016102199190610a7e565b506103db6402540be400677fffffffffffffff6109df565b8111156103e78261072c565b6104016103806402540be400677fffffffffffffff6109df565b604051602001610412929190610ab3565b6040516020818303038152906040529061043f5760405162461bcd60e51b81526004016102199190610a7e565b50335f9081526020819052604090205480151561045b8261072c565b60405160200161046b9190610b02565b604051602081830303815290604052906104985760405162461bcd60e51b81526004016102199190610a7e565b505f6104a96402540be40084610b4d565b9050826104bb6402540be400836109df565b146104c58461072c565b6104d36402540be40061072c565b6040516020016104e4929190610b6c565b604051602081830303815290604052906105115760405162461bcd60e51b81526004016102199190610a7e565b50435f8181526001602052604090205461ffff16610400908111906105359061072c565b6040516020016105459190610bbb565b604051602081830303815290604052906105725760405162461bcd60e51b81526004016102199190610a7e565b50305f9081526002602052604090205460ff166105d15760405162461bcd60e51b815260206004820152601760248201527f546f6b656e206973206e6f7420726567697374657265640000000000000000006044820152606401610219565b5f818152600160205260408120805461ffff16916105ee83610c29565b91906101000a81548161ffff021916908361ffff160217905550506106133385610852565b604080516bffffffffffffffffffffffff1987168152600784900b6020820152308183015290517fdc193e59112938988cb57c4abdeb47ac3889408ba6f39d7061bee9476907bedc9181900360600190a15050505050565b61034e6402540be40060016109df565b5f5b818110156106e057600160025f85858581811061069c5761069c610c49565b90506020020160208101906106b19190610894565b6001600160a01b0316815260208101919091526040015f20805460ff191691151591909117905560010161067d565b50604080515f815260208101918290527ff99b9087e9b3cd03d2ded305c68b24657503e9c64536e2e3f235355fac94bfa991610720918591859190610c71565b60405180910390a15050565b6060815f036107525750506040805180820190915260018152600360fc1b602082015290565b815f5b811561077b578061076581610d01565b91506107749050600a83610b4d565b9150610755565b5f8167ffffffffffffffff81111561079557610795610c5d565b6040519080825280601f01601f1916602001820160405280156107bf576020820181803683370190505b509050815b8515610849576107d5600182610d19565b90505f6107e3600a88610b4d565b6107ee90600a6109df565b6107f89088610d19565b610803906030610d2c565b90505f8160f81b90508084848151811061081f5761081f610c49565b60200101906001600160f81b03191690815f1a905350610840600a89610b4d565b975050506107c4565b50949350505050565b6001600160a01b0382165f908152602081905260408120805483929061032e908490610d19565b80356001600160a01b038116811461088f575f80fd5b919050565b5f602082840312156108a4575f80fd5b6108ad82610879565b9392505050565b5f80604083850312156108c5575f80fd5b6108ce83610879565b915060208301358060070b81146108e3575f80fd5b809150509250929050565b5f602082840312156108fe575f80fd5b5035919050565b5f8060408385031215610916575f80fd5b61091f83610879565b946020939093013593505050565b5f806040838503121561093e575f80fd5b82356bffffffffffffffffffffffff198116811461091f575f80fd5b5f806020838503121561096b575f80fd5b823567ffffffffffffffff811115610981575f80fd5b8301601f81018513610991575f80fd5b803567ffffffffffffffff8111156109a7575f80fd5b8560208260051b84010111156109bb575f80fd5b6020919091019590945092505050565b634e487b7160e01b5f52601160045260245ffd5b80820281158282048414176109f6576109f66109cb565b92915050565b808201808211156109f6576109f66109cb565b5f81518060208401855e5f93019283525090919050565b6a029b2b73a103b30b63ab2960ad1b81525f610a45600b830185610a0f565b7f206d7573742062652067726561746572206f7220657175616c20746f200000008152610a75601d820185610a0f565b95945050505050565b602081525f82518060208401528060208501604085015e5f604082850101526040601f19601f83011684010191505092915050565b6a029b2b73a103b30b63ab2960ad1b81525f610ad2600b830185610a0f565b7f206d757374206265206c657373206f7220657175616c20746f200000000000008152610a75601a820185610a0f565b7f496e73756666696369656e742066756e64732c206f6e6c79200000000000000081525f610b336019830184610a0f565b6920617661696c61626c6560b01b8152600a019392505050565b5f82610b6757634e487b7160e01b5f52601260045260245ffd5b500490565b6a029b2b73a103b30b63ab2960ad1b81525f610b8b600b830185610a0f565b7f206d7573742062652061206d756c7469706c65206f66200000000000000000008152610a756017820185610a0f565b7f4d6178207472616e7366657273206c696d6974206f662000000000000000000081525f610bec6017830184610a0f565b7f207265616368656420696e207468697320626c6f636b2e20547279206167616981526637103630ba32b960c91b60208201526027019392505050565b5f61ffff821661ffff8103610c4057610c406109cb565b60010192915050565b634e487b7160e01b5f52603260045260245ffd5b634e487b7160e01b5f52604160045260245ffd5b604080825281018390525f8460608301825b86811015610cb1576001600160a01b03610c9c84610879565b16825260209283019290910190600101610c83565b50838103602080860191909152855180835291810192508501905f5b81811015610cf45782516001600160a01b0316845260209384019390920191600101610ccd565b5091979650505050505050565b5f60018201610d1257610d126109cb565b5060010190565b818103818111156109f6576109f66109cb565b60ff81811683821601908111156109f6576109f66109cb56fea2646970667358221220c951968bb4771f3bb9f38137ff5a6f3e7a3e23d3d23404294fb3c52258ed556164736f6c634300081a0033";

    private static String librariesLinkedBinary;

    public static final String FUNC_EL_TO_CL_RATIO = "EL_TO_CL_RATIO";

    public static final String FUNC_MAX_AMOUNT_IN_WEI = "MAX_AMOUNT_IN_WEI";

    public static final String FUNC_MAX_TRANSFERS_IN_BLOCK = "MAX_TRANSFERS_IN_BLOCK";

    public static final String FUNC_MIN_AMOUNT_IN_WEI = "MIN_AMOUNT_IN_WEI";

    public static final String FUNC_BALANCES = "balances";

    public static final String FUNC_BRIDGEERC20 = "bridgeERC20";

    public static final String FUNC_FINALIZEBRIDGEERC20 = "finalizeBridgeERC20";

    public static final String FUNC_MINT = "mint";

    public static final String FUNC_TOKENREGISTRY = "tokenRegistry";

    public static final String FUNC_TRANSFERSPERBLOCK = "transfersPerBlock";

    public static final String FUNC_UPDATETOKENREGISTRY = "updateTokenRegistry";

    public static final Event ERC20BRIDGEFINALIZED_EVENT = new Event("ERC20BridgeFinalized", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {}, new TypeReference<Long>() {}, new TypeReference<Address>() {}));
    ;

    public static final Event ERC20BRIDGEINITIATED_EVENT = new Event("ERC20BridgeInitiated", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Bytes20>() {}, new TypeReference<Long>() {}, new TypeReference<Address>() {}));
    ;

    public static final Event REGISTRYUPDATED_EVENT = new Event("RegistryUpdated", 
            Arrays.<TypeReference<?>>asList(new TypeReference<DynamicArray<Address>>() {}, new TypeReference<DynamicArray<Address>>() {}));
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

    public static List<RegistryUpdatedEventResponse> getRegistryUpdatedEvents(
            TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(REGISTRYUPDATED_EVENT, transactionReceipt);
        ArrayList<RegistryUpdatedEventResponse> responses = new ArrayList<RegistryUpdatedEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            RegistryUpdatedEventResponse typedResponse = new RegistryUpdatedEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.added = (List<String>) ((Array) eventValues.getNonIndexedValues().get(0)).getNativeValueCopy();
            typedResponse.removed = (List<String>) ((Array) eventValues.getNonIndexedValues().get(1)).getNativeValueCopy();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static RegistryUpdatedEventResponse getRegistryUpdatedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(REGISTRYUPDATED_EVENT, log);
        RegistryUpdatedEventResponse typedResponse = new RegistryUpdatedEventResponse();
        typedResponse.log = log;
        typedResponse.added = (List<String>) ((Array) eventValues.getNonIndexedValues().get(0)).getNativeValueCopy();
        typedResponse.removed = (List<String>) ((Array) eventValues.getNonIndexedValues().get(1)).getNativeValueCopy();
        return typedResponse;
    }

    public Flowable<RegistryUpdatedEventResponse> registryUpdatedEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getRegistryUpdatedEventFromLog(log));
    }

    public Flowable<RegistryUpdatedEventResponse> registryUpdatedEventFlowable(
            DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(REGISTRYUPDATED_EVENT));
        return registryUpdatedEventFlowable(filter);
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

    public RemoteFunctionCall<Boolean> call_tokenRegistry(String param0) {
        final Function function = new Function(FUNC_TOKENREGISTRY, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Bool>() {}));
        return executeRemoteCallSingleValueReturn(function, Boolean.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_tokenRegistry(String param0) {
        final Function function = new Function(
                FUNC_TOKENREGISTRY, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
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

    public RemoteFunctionCall<TransactionReceipt> send_updateTokenRegistry(List<String> added) {
        final Function function = new Function(
                FUNC_UPDATETOKENREGISTRY, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.DynamicArray<org.web3j.abi.datatypes.Address>(
                        org.web3j.abi.datatypes.Address.class,
                        org.web3j.abi.Utils.typeMap(added, org.web3j.abi.datatypes.Address.class))), 
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

    public static class RegistryUpdatedEventResponse extends BaseEventResponse {
        public List<String> added;

        public List<String> removed;
    }
}
