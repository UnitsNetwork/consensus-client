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
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes20;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint8;
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
    public static final String BINARY = "6080604052348015600e575f80fd5b5061111e8061001c5f395ff3fe608060405234801561000f575f80fd5b5060043610610085575f3560e01c806340c10f191161005857806340c10f19146101195780636722fb501461012c5780637157405a1461016c578063fb3db41a14610175575f80fd5b8063044a2ef91461008957806322173e541461009e57806327e235e3146100b157806339dd5d1b146100e3575b5f80fd5b61009c610097366004610a0e565b610188565b005b61009c6100ac366004610a55565b610351565b6100d06100bf366004610a98565b5f6020819052908152604090205481565b6040519081526020015b60405180910390f35b6101066100f1366004610ab8565b60016020525f908152604090205461ffff1681565b60405161ffff90911681526020016100da565b61009c610127366004610acf565b61067a565b61015461013a366004610a98565b60026020525f90815260409020546001600160401b031681565b6040516001600160401b0390911681526020016100da565b61010661040081565b61009c610183366004610b3e565b6106aa565b5f8260070b136101f35760405162461bcd60e51b815260206004820152602b60248201527f526563656976652076616c7565206d7573742062652067726561746572206f7260448201526a020657175616c20746f20360ac1b60648201526084015b60405180910390fd5b6001600160a01b0381165f908152600260205260409020546001600160401b03168061025b5760405162461bcd60e51b8152602060048201526017602482015276151bdad95b881a5cc81b9bdd081c9959da5cdd195c9959604a1b60448201526064016101ea565b5f6102736001600160401b038316600786900b610bbc565b90505f6102916001600160401b038416677fffffffffffffff610bbc565b9050808211156102f25760405162461bcd60e51b815260206004820152602660248201527f416d6f756e742065786365656473206d6178696d756d20616c6c6f7761626c656044820152652076616c756560d01b60648201526084016101ea565b6102fc868361067a565b604080516001600160a01b038881168252600788900b602083015286168183015290517f79ba61b7b220c73204f9deefe02364b84339a67c836feb371b0b7c9860084d5e9181900360600190a1505050505050565b6001600160a01b0381165f908152600260205260409020546001600160401b0316806103b95760405162461bcd60e51b8152602060048201526017602482015276151bdad95b881a5cc81b9bdd081c9959da5cdd195c9959604a1b60448201526064016101ea565b5f6103c5826001610bd9565b6001600160401b0390811691505f906103e8908416677fffffffffffffff610bbc565b9050818510156103f7866108a7565b610400846108a7565b604051602001610411929190610c19565b6040516020818303038152906040529061043e5760405162461bcd60e51b81526004016101ea9190610c71565b508085111561044c866108a7565b610455836108a7565b604051602001610466929190610ca6565b604051602081830303815290604052906104935760405162461bcd60e51b81526004016101ea9190610c71565b50335f908152602081905260409020548581116104af826108a7565b6040516020016104bf9190610cf5565b604051602081830303815290604052906104ec5760405162461bcd60e51b81526004016101ea9190610c71565b505f6105016001600160401b03861688610d40565b9050866105176001600160401b03871683610bbc565b14610521886108a7565b610533876001600160401b03166108a7565b604051602001610544929190610d5f565b604051602081830303815290604052906105715760405162461bcd60e51b81526004016101ea9190610c71565b50435f8181526001602052604090205461ffff1661040090811190610595906108a7565b6040516020016105a59190610dae565b604051602081830303815290604052906105d25760405162461bcd60e51b81526004016101ea9190610c71565b505f818152600160205260408120805461ffff16916105f083610e1c565b91906101000a81548161ffff021916908361ffff1602179055505061061533896109cc565b604080516bffffffffffffffffffffffff198b168152600784900b60208201526001600160a01b0389168183015290517fdc193e59112938988cb57c4abdeb47ac3889408ba6f39d7061bee9476907bedc9181900360600190a1505050505050505050565b6001600160a01b0382165f90815260208190526040812080548392906106a1908490610e3c565b90915550505050565b8281146107155760405162461bcd60e51b815260206004820152603360248201527f446966666572656e742073697a6573206f662061646465642061737365747320604482015272616e64207468656972206578706f6e656e747360681b60648201526084016101ea565b5f5b83811015610837575f83838381811061073257610732610e4f565b90506020020160208101906107479190610e73565b9050600a8160ff16111561075d8260ff166108a7565b60405160200161076d9190610e8c565b6040516020818303038152906040529061079a5760405162461bcd60e51b81526004016101ea9190610c71565b508383838181106107ad576107ad610e4f565b90506020020160208101906107c29190610e73565b6107cd90600a610fa0565b60025f8888868181106107e2576107e2610e4f565b90506020020160208101906107f79190610a98565b6001600160a01b0316815260208101919091526040015f20805467ffffffffffffffff19166001600160401b039290921691909117905550600101610717565b507f6f1865b1fb41fed526e00bdd475fbb6a25987b5a14e360a4357f68b573b736e7848484845f604051908082528060200260200182016040528015610887578160200160208202803683370190505b50604051610899959493929190611005565b60405180910390a150505050565b6060815f036108cd5750506040805180820190915260018152600360fc1b602082015290565b815f5b81156108f657806108e0816110a4565b91506108ef9050600a83610d40565b91506108d0565b5f816001600160401b0381111561090f5761090f610fae565b6040519080825280601f01601f191660200182016040528015610939576020820181803683370190505b509050815b85156109c35761094f6001826110bc565b90505f61095d600a88610d40565b61096890600a610bbc565b61097290886110bc565b61097d9060306110cf565b90505f8160f81b90508084848151811061099957610999610e4f565b60200101906001600160f81b03191690815f1a9053506109ba600a89610d40565b9750505061093e565b50949350505050565b6001600160a01b0382165f90815260208190526040812080548392906106a19084906110bc565b80356001600160a01b0381168114610a09575f80fd5b919050565b5f805f60608486031215610a20575f80fd5b610a29846109f3565b925060208401358060070b8114610a3e575f80fd5b9150610a4c604085016109f3565b90509250925092565b5f805f60608486031215610a67575f80fd5b83356bffffffffffffffffffffffff1981168114610a83575f80fd5b925060208401359150610a4c604085016109f3565b5f60208284031215610aa8575f80fd5b610ab1826109f3565b9392505050565b5f60208284031215610ac8575f80fd5b5035919050565b5f8060408385031215610ae0575f80fd5b610ae9836109f3565b946020939093013593505050565b5f8083601f840112610b07575f80fd5b5081356001600160401b03811115610b1d575f80fd5b6020830191508360208260051b8501011115610b37575f80fd5b9250929050565b5f805f8060408587031215610b51575f80fd5b84356001600160401b03811115610b66575f80fd5b610b7287828801610af7565b90955093505060208501356001600160401b03811115610b90575f80fd5b610b9c87828801610af7565b95989497509550505050565b634e487b7160e01b5f52601160045260245ffd5b8082028115828204841417610bd357610bd3610ba8565b92915050565b6001600160401b038181168382160290811690818114610bfb57610bfb610ba8565b5092915050565b5f81518060208401855e5f93019283525090919050565b6a029b2b73a103b30b63ab2960ad1b81525f610c38600b830185610c02565b7f206d7573742062652067726561746572206f7220657175616c20746f200000008152610c68601d820185610c02565b95945050505050565b602081525f82518060208401528060208501604085015e5f604082850101526040601f19601f83011684010191505092915050565b6a029b2b73a103b30b63ab2960ad1b81525f610cc5600b830185610c02565b7f206d757374206265206c657373206f7220657175616c20746f200000000000008152610c68601a820185610c02565b7f496e73756666696369656e742066756e64732c206f6e6c79200000000000000081525f610d266019830184610c02565b6920617661696c61626c6560b01b8152600a019392505050565b5f82610d5a57634e487b7160e01b5f52601260045260245ffd5b500490565b6a029b2b73a103b30b63ab2960ad1b81525f610d7e600b830185610c02565b7f206d7573742062652061206d756c7469706c65206f66200000000000000000008152610c686017820185610c02565b7f4d6178207472616e7366657273206c696d6974206f662000000000000000000081525f610ddf6017830184610c02565b7f207265616368656420696e207468697320626c6f636b2e20547279206167616981526637103630ba32b960c91b60208201526027019392505050565b5f61ffff821661ffff8103610e3357610e33610ba8565b60010192915050565b80820180821115610bd357610bd3610ba8565b634e487b7160e01b5f52603260045260245ffd5b803560ff81168114610a09575f80fd5b5f60208284031215610e83575f80fd5b610ab182610e63565b7f496e76616c6964206173736574206578706f6e656e743a20000000000000000081525f610ab16018830184610c02565b6001815b6001841115610ef857808504811115610edc57610edc610ba8565b6001841615610eea57908102905b60019390931c928002610ec1565b935093915050565b5f82610f0e57506001610bd3565b81610f1a57505f610bd3565b8160018114610f305760028114610f3a57610f56565b6001915050610bd3565b60ff841115610f4b57610f4b610ba8565b50506001821b610bd3565b5060208310610133831016604e8410600b8410161715610f79575081810a610bd3565b610f855f198484610ebd565b805f1904821115610f9857610f98610ba8565b029392505050565b5f610ab160ff841683610f00565b634e487b7160e01b5f52604160045260245ffd5b5f8151808452602084019350602083015f5b82811015610ffb5781516001600160a01b0316865260209586019590910190600101610fd4565b5093949350505050565b606080825281018590525f8660808301825b88811015611045576001600160a01b03611030846109f3565b16825260209283019290910190600101611017565b50838103602080860191909152868252019050855f5b868110156110845760ff61106e83610e63565b168352602092830192919091019060010161105b565b505082810360408401526110988185610fc2565b98975050505050505050565b5f600182016110b5576110b5610ba8565b5060010190565b81810381811115610bd357610bd3610ba8565b60ff8181168382160190811115610bd357610bd3610ba856fea2646970667358221220bc270d75475ca4caf379e8a0bfceb6c55df898b4a18360363a74944fc55078ad64736f6c634300081a0033";

    private static String librariesLinkedBinary;

    public static final String FUNC_MAX_TRANSFERS_IN_BLOCK = "MAX_TRANSFERS_IN_BLOCK";

    public static final String FUNC_BALANCES = "balances";

    public static final String FUNC_BRIDGEERC20 = "bridgeERC20";

    public static final String FUNC_FINALIZEBRIDGEERC20 = "finalizeBridgeERC20";

    public static final String FUNC_MINT = "mint";

    public static final String FUNC_TOKENSRATIO = "tokensRatio";

    public static final String FUNC_TRANSFERSPERBLOCK = "transfersPerBlock";

    public static final String FUNC_UPDATETOKENREGISTRY = "updateTokenRegistry";

    public static final Event ERC20BRIDGEFINALIZED_EVENT = new Event("ERC20BridgeFinalized", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {}, new TypeReference<Long>() {}, new TypeReference<Address>() {}));
    ;

    public static final Event ERC20BRIDGEINITIATED_EVENT = new Event("ERC20BridgeInitiated", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Bytes20>() {}, new TypeReference<Long>() {}, new TypeReference<Address>() {}));
    ;

    public static final Event REGISTRYUPDATED_EVENT = new Event("RegistryUpdated", 
            Arrays.<TypeReference<?>>asList(new TypeReference<DynamicArray<Address>>() {}, new TypeReference<DynamicArray<Uint8>>() {}, new TypeReference<DynamicArray<Address>>() {}));
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
            typedResponse.addedAssets = (List<String>) ((Array) eventValues.getNonIndexedValues().get(0)).getNativeValueCopy();
            typedResponse.addedAssetExponents = (List<BigInteger>) ((Array) eventValues.getNonIndexedValues().get(1)).getNativeValueCopy();
            typedResponse.removed = (List<String>) ((Array) eventValues.getNonIndexedValues().get(2)).getNativeValueCopy();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static RegistryUpdatedEventResponse getRegistryUpdatedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(REGISTRYUPDATED_EVENT, log);
        RegistryUpdatedEventResponse typedResponse = new RegistryUpdatedEventResponse();
        typedResponse.log = log;
        typedResponse.addedAssets = (List<String>) ((Array) eventValues.getNonIndexedValues().get(0)).getNativeValueCopy();
        typedResponse.addedAssetExponents = (List<BigInteger>) ((Array) eventValues.getNonIndexedValues().get(1)).getNativeValueCopy();
        typedResponse.removed = (List<String>) ((Array) eventValues.getNonIndexedValues().get(2)).getNativeValueCopy();
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
            BigInteger elAmount, String asset) {
        final Function function = new Function(
                FUNC_BRIDGEERC20, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Bytes20(wavesRecipient), 
                new org.web3j.abi.datatypes.generated.Uint256(elAmount), 
                new org.web3j.abi.datatypes.Address(160, asset)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> send_finalizeBridgeERC20(String recipient,
            java.lang.Long clAmount, String asset) {
        final Function function = new Function(
                FUNC_FINALIZEBRIDGEERC20, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, recipient), 
                new org.web3j.abi.datatypes.primitive.Long(clAmount), 
                new org.web3j.abi.datatypes.Address(160, asset)), 
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

    public RemoteFunctionCall<BigInteger> call_tokensRatio(String param0) {
        final Function function = new Function(FUNC_TOKENSRATIO, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint64>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_tokensRatio(String param0) {
        final Function function = new Function(
                FUNC_TOKENSRATIO, 
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

    public RemoteFunctionCall<TransactionReceipt> send_updateTokenRegistry(List<String> addedAssets,
            List<BigInteger> addedAssetExponents) {
        final Function function = new Function(
                FUNC_UPDATETOKENREGISTRY, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.DynamicArray<org.web3j.abi.datatypes.Address>(
                        org.web3j.abi.datatypes.Address.class,
                        org.web3j.abi.Utils.typeMap(addedAssets, org.web3j.abi.datatypes.Address.class)), 
                new org.web3j.abi.datatypes.DynamicArray<org.web3j.abi.datatypes.generated.Uint8>(
                        org.web3j.abi.datatypes.generated.Uint8.class,
                        org.web3j.abi.Utils.typeMap(addedAssetExponents, org.web3j.abi.datatypes.generated.Uint8.class))), 
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
        public List<String> addedAssets;

        public List<BigInteger> addedAssetExponents;

        public List<String> removed;
    }
}
