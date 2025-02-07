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
public class StandardBridgeContract extends Contract {
    public static final String BINARY = "6080604052348015600e575f80fd5b506111268061001c5f395ff3fe608060405234801561000f575f80fd5b5060043610610085575f3560e01c80634a9a77f9116100585780634a9a77f914610119578063679936eb1461012c5780637157405a1461016c578063770dddd514610175575f80fd5b80631744f1671461008957806327e235e31461009e57806339dd5d1b146100d057806340c10f1914610106575b5f80fd5b61009c610097366004610a3a565b610188565b005b6100bd6100ac366004610abf565b5f6020819052908152604090205481565b6040519081526020015b60405180910390f35b6100f36100de366004610adf565b60016020525f908152604090205461ffff1681565b60405161ffff90911681526020016100c7565b61009c610114366004610af6565b61038a565b61009c610127366004610b1e565b6103ba565b61015461013a366004610abf565b60026020525f90815260409020546001600160401b031681565b6040516001600160401b0390911681526020016100c7565b6100f361040081565b61009c610183366004610b6b565b6106e3565b8281146101f85760405162461bcd60e51b815260206004820152603360248201527f446966666572656e742073697a6573206f6620616464656420746f6b656e7320604482015272616e64207468656972206578706f6e656e747360681b60648201526084015b60405180910390fd5b5f5b8381101561031a575f83838381811061021557610215610bb4565b905060200201602081019061022a9190610bd8565b9050600a8160ff1611156102408260ff166108a7565b6040516020016102509190610c08565b6040516020818303038152906040529061027d5760405162461bcd60e51b81526004016101ef9190610c39565b5083838381811061029057610290610bb4565b90506020020160208101906102a59190610bd8565b6102b090600a610d67565b60025f8888868181106102c5576102c5610bb4565b90506020020160208101906102da9190610abf565b6001600160a01b0316815260208101919091526040015f20805467ffffffffffffffff19166001600160401b0392909216919091179055506001016101fa565b507f6f1865b1fb41fed526e00bdd475fbb6a25987b5a14e360a4357f68b573b736e7848484845f60405190808252806020026020018201604052801561036a578160200160208202803683370190505b5060405161037c959493929190610dcc565b60405180910390a150505050565b6001600160a01b0382165f90815260208190526040812080548392906103b1908490610e6b565b90915550505050565b6001600160a01b0383165f908152600260205260409020546001600160401b0316806104225760405162461bcd60e51b8152602060048201526017602482015276151bdad95b881a5cc81b9bdd081c9959da5cdd195c9959604a1b60448201526064016101ef565b5f61042e826001610e7e565b6001600160401b0390811691505f90610451908416677fffffffffffffff610ea7565b905081841015610460856108a7565b610469846108a7565b60405160200161047a929190610ebe565b604051602081830303815290604052906104a75760405162461bcd60e51b81526004016101ef9190610c39565b50808411156104b5856108a7565b6104be836108a7565b6040516020016104cf929190610f16565b604051602081830303815290604052906104fc5760405162461bcd60e51b81526004016101ef9190610c39565b50335f90815260208190526040902054848111610518826108a7565b6040516020016105289190610f65565b604051602081830303815290604052906105555760405162461bcd60e51b81526004016101ef9190610c39565b505f61056a6001600160401b03861687610fb0565b9050856105806001600160401b03871683610ea7565b1461058a876108a7565b61059c876001600160401b03166108a7565b6040516020016105ad929190610fcf565b604051602081830303815290604052906105da5760405162461bcd60e51b81526004016101ef9190610c39565b50435f8181526001602052604090205461ffff16610400908111906105fe906108a7565b60405160200161060e919061101e565b6040516020818303038152906040529061063b5760405162461bcd60e51b81526004016101ef9190610c39565b505f818152600160205260408120805461ffff16916106598361108c565b91906101000a81548161ffff021916908361ffff1602179055505061067e33886109cc565b604080516001600160a01b038b1681526bffffffffffffffffffffffff198a166020820152600784900b8183015290517f5cd488491627f735bfc593caa4019d0b3758695e8c880d3e6871cba381c6678d9181900360600190a1505050505050505050565b5f8160070b136107495760405162461bcd60e51b815260206004820152602b60248201527f526563656976652076616c7565206d7573742062652067726561746572206f7260448201526a020657175616c20746f20360ac1b60648201526084016101ef565b6001600160a01b0383165f908152600260205260409020546001600160401b0316806107b15760405162461bcd60e51b8152602060048201526017602482015276151bdad95b881a5cc81b9bdd081c9959da5cdd195c9959604a1b60448201526064016101ef565b5f6107c96001600160401b038316600785900b610ea7565b90505f6107e76001600160401b038416677fffffffffffffff610ea7565b9050808211156108485760405162461bcd60e51b815260206004820152602660248201527f416d6f756e742065786365656473206d6178696d756d20616c6c6f7761626c656044820152652076616c756560d01b60648201526084016101ef565b610852858361038a565b604080516001600160a01b03888116825287166020820152600786900b8183015290517f9b1907e4a2ebccf68c3316aa0a3360f8d381cf808acdd35f74d8e737e2f2c7a49181900360600190a1505050505050565b6060815f036108cd5750506040805180820190915260018152600360fc1b602082015290565b815f5b81156108f657806108e0816110ac565b91506108ef9050600a83610fb0565b91506108d0565b5f816001600160401b0381111561090f5761090f610d75565b6040519080825280601f01601f191660200182016040528015610939576020820181803683370190505b509050815b85156109c35761094f6001826110c4565b90505f61095d600a88610fb0565b61096890600a610ea7565b61097290886110c4565b61097d9060306110d7565b90505f8160f81b90508084848151811061099957610999610bb4565b60200101906001600160f81b03191690815f1a9053506109ba600a89610fb0565b9750505061093e565b50949350505050565b6001600160a01b0382165f90815260208190526040812080548392906103b19084906110c4565b5f8083601f840112610a03575f80fd5b5081356001600160401b03811115610a19575f80fd5b6020830191508360208260051b8501011115610a33575f80fd5b9250929050565b5f805f8060408587031215610a4d575f80fd5b84356001600160401b03811115610a62575f80fd5b610a6e878288016109f3565b90955093505060208501356001600160401b03811115610a8c575f80fd5b610a98878288016109f3565b95989497509550505050565b80356001600160a01b0381168114610aba575f80fd5b919050565b5f60208284031215610acf575f80fd5b610ad882610aa4565b9392505050565b5f60208284031215610aef575f80fd5b5035919050565b5f8060408385031215610b07575f80fd5b610b1083610aa4565b946020939093013593505050565b5f805f60608486031215610b30575f80fd5b610b3984610aa4565b925060208401356bffffffffffffffffffffffff1981168114610b5a575f80fd5b929592945050506040919091013590565b5f805f60608486031215610b7d575f80fd5b610b8684610aa4565b9250610b9460208501610aa4565b915060408401358060070b8114610ba9575f80fd5b809150509250925092565b634e487b7160e01b5f52603260045260245ffd5b803560ff81168114610aba575f80fd5b5f60208284031215610be8575f80fd5b610ad882610bc8565b5f81518060208401855e5f93019283525090919050565b7f496e76616c696420746f6b656e206578706f6e656e743a20000000000000000081525f610ad86018830184610bf1565b602081525f82518060208401528060208501604085015e5f604082850101526040601f19601f83011684010191505092915050565b634e487b7160e01b5f52601160045260245ffd5b6001815b6001841115610cbd57808504811115610ca157610ca1610c6e565b6001841615610caf57908102905b60019390931c928002610c86565b935093915050565b5f82610cd357506001610d61565b81610cdf57505f610d61565b8160018114610cf55760028114610cff57610d1b565b6001915050610d61565b60ff841115610d1057610d10610c6e565b50506001821b610d61565b5060208310610133831016604e8410600b8410161715610d3e575081810a610d61565b610d4a5f198484610c82565b805f1904821115610d5d57610d5d610c6e565b0290505b92915050565b5f610ad860ff841683610cc5565b634e487b7160e01b5f52604160045260245ffd5b5f8151808452602084019350602083015f5b82811015610dc25781516001600160a01b0316865260209586019590910190600101610d9b565b5093949350505050565b606080825281018590525f8660808301825b88811015610e0c576001600160a01b03610df784610aa4565b16825260209283019290910190600101610dde565b50838103602080860191909152868252019050855f5b86811015610e4b5760ff610e3583610bc8565b1683526020928301929190910190600101610e22565b50508281036040840152610e5f8185610d89565b98975050505050505050565b80820180821115610d6157610d61610c6e565b6001600160401b038181168382160290811690818114610ea057610ea0610c6e565b5092915050565b8082028115828204841417610d6157610d61610c6e565b6a029b2b73a103b30b63ab2960ad1b81525f610edd600b830185610bf1565b7f206d7573742062652067726561746572206f7220657175616c20746f200000008152610f0d601d820185610bf1565b95945050505050565b6a029b2b73a103b30b63ab2960ad1b81525f610f35600b830185610bf1565b7f206d757374206265206c657373206f7220657175616c20746f200000000000008152610f0d601a820185610bf1565b7f496e73756666696369656e742066756e64732c206f6e6c79200000000000000081525f610f966019830184610bf1565b6920617661696c61626c6560b01b8152600a019392505050565b5f82610fca57634e487b7160e01b5f52601260045260245ffd5b500490565b6a029b2b73a103b30b63ab2960ad1b81525f610fee600b830185610bf1565b7f206d7573742062652061206d756c7469706c65206f66200000000000000000008152610f0d6017820185610bf1565b7f4d6178207472616e7366657273206c696d6974206f662000000000000000000081525f61104f6017830184610bf1565b7f207265616368656420696e207468697320626c6f636b2e20547279206167616981526637103630ba32b960c91b60208201526027019392505050565b5f61ffff821661ffff81036110a3576110a3610c6e565b60010192915050565b5f600182016110bd576110bd610c6e565b5060010190565b81810381811115610d6157610d61610c6e565b60ff8181168382160190811115610d6157610d61610c6e56fea264697066735822122027943802004051981d1ba1d543e742bbc8e205f7094847c4eadd64df153af78c64736f6c634300081a0033";

    private static String librariesLinkedBinary;

    public static final String FUNC_MAX_TRANSFERS_IN_BLOCK = "MAX_TRANSFERS_IN_BLOCK";

    public static final String FUNC_BALANCES = "balances";

    public static final String FUNC_BRIDGEERC20 = "bridgeERC20";

    public static final String FUNC_FINALIZEBRIDGEERC20 = "finalizeBridgeERC20";

    public static final String FUNC_MINT = "mint";

    public static final String FUNC_TOKENRATIOS = "tokenRatios";

    public static final String FUNC_TRANSFERSPERBLOCK = "transfersPerBlock";

    public static final String FUNC_UPDATEASSETREGISTRY = "updateAssetRegistry";

    public static final Event ERC20BRIDGEFINALIZED_EVENT = new Event("ERC20BridgeFinalized", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {}, new TypeReference<Address>() {}, new TypeReference<Long>() {}));
    ;

    public static final Event ERC20BRIDGEINITIATED_EVENT = new Event("ERC20BridgeInitiated", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {}, new TypeReference<Bytes20>() {}, new TypeReference<Long>() {}));
    ;

    public static final Event REGISTRYUPDATED_EVENT = new Event("RegistryUpdated", 
            Arrays.<TypeReference<?>>asList(new TypeReference<DynamicArray<Address>>() {}, new TypeReference<DynamicArray<Uint8>>() {}, new TypeReference<DynamicArray<Address>>() {}));
    ;

    @Deprecated
    protected StandardBridgeContract(String contractAddress, Web3j web3j, Credentials credentials,
            BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    protected StandardBridgeContract(String contractAddress, Web3j web3j, Credentials credentials,
            ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, credentials, contractGasProvider);
    }

    @Deprecated
    protected StandardBridgeContract(String contractAddress, Web3j web3j,
            TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    protected StandardBridgeContract(String contractAddress, Web3j web3j,
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
            typedResponse.localToken = (String) eventValues.getNonIndexedValues().get(0).getValue();
            typedResponse.elTo = (String) eventValues.getNonIndexedValues().get(1).getValue();
            typedResponse.clAmount = (java.lang.Long) eventValues.getNonIndexedValues().get(2).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static ERC20BridgeFinalizedEventResponse getERC20BridgeFinalizedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(ERC20BRIDGEFINALIZED_EVENT, log);
        ERC20BridgeFinalizedEventResponse typedResponse = new ERC20BridgeFinalizedEventResponse();
        typedResponse.log = log;
        typedResponse.localToken = (String) eventValues.getNonIndexedValues().get(0).getValue();
        typedResponse.elTo = (String) eventValues.getNonIndexedValues().get(1).getValue();
        typedResponse.clAmount = (java.lang.Long) eventValues.getNonIndexedValues().get(2).getValue();
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
            typedResponse.localToken = (String) eventValues.getNonIndexedValues().get(0).getValue();
            typedResponse.clTo = (byte[]) eventValues.getNonIndexedValues().get(1).getValue();
            typedResponse.clAmount = (java.lang.Long) eventValues.getNonIndexedValues().get(2).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static ERC20BridgeInitiatedEventResponse getERC20BridgeInitiatedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(ERC20BRIDGEINITIATED_EVENT, log);
        ERC20BridgeInitiatedEventResponse typedResponse = new ERC20BridgeInitiatedEventResponse();
        typedResponse.log = log;
        typedResponse.localToken = (String) eventValues.getNonIndexedValues().get(0).getValue();
        typedResponse.clTo = (byte[]) eventValues.getNonIndexedValues().get(1).getValue();
        typedResponse.clAmount = (java.lang.Long) eventValues.getNonIndexedValues().get(2).getValue();
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
            typedResponse.addedTokens = (List<String>) ((Array) eventValues.getNonIndexedValues().get(0)).getNativeValueCopy();
            typedResponse.addedTokenExponents = (List<BigInteger>) ((Array) eventValues.getNonIndexedValues().get(1)).getNativeValueCopy();
            typedResponse.removedTokens = (List<String>) ((Array) eventValues.getNonIndexedValues().get(2)).getNativeValueCopy();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static RegistryUpdatedEventResponse getRegistryUpdatedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(REGISTRYUPDATED_EVENT, log);
        RegistryUpdatedEventResponse typedResponse = new RegistryUpdatedEventResponse();
        typedResponse.log = log;
        typedResponse.addedTokens = (List<String>) ((Array) eventValues.getNonIndexedValues().get(0)).getNativeValueCopy();
        typedResponse.addedTokenExponents = (List<BigInteger>) ((Array) eventValues.getNonIndexedValues().get(1)).getNativeValueCopy();
        typedResponse.removedTokens = (List<String>) ((Array) eventValues.getNonIndexedValues().get(2)).getNativeValueCopy();
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

    public RemoteFunctionCall<TransactionReceipt> send_bridgeERC20(String token, byte[] clTo,
            BigInteger elAmount) {
        final Function function = new Function(
                FUNC_BRIDGEERC20, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, token), 
                new org.web3j.abi.datatypes.generated.Bytes20(clTo), 
                new org.web3j.abi.datatypes.generated.Uint256(elAmount)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> send_finalizeBridgeERC20(String token,
            String elTo, java.lang.Long clAmount) {
        final Function function = new Function(
                FUNC_FINALIZEBRIDGEERC20, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, token), 
                new org.web3j.abi.datatypes.Address(160, elTo), 
                new org.web3j.abi.datatypes.primitive.Long(clAmount)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> send_mint(String to, BigInteger elAmount) {
        final Function function = new Function(
                FUNC_MINT, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, to), 
                new org.web3j.abi.datatypes.generated.Uint256(elAmount)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<BigInteger> call_tokenRatios(String param0) {
        final Function function = new Function(FUNC_TOKENRATIOS, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint64>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_tokenRatios(String param0) {
        final Function function = new Function(
                FUNC_TOKENRATIOS, 
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

    public RemoteFunctionCall<TransactionReceipt> send_updateAssetRegistry(List<String> addedTokens,
            List<BigInteger> addedTokenExponents) {
        final Function function = new Function(
                FUNC_UPDATEASSETREGISTRY, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.DynamicArray<org.web3j.abi.datatypes.Address>(
                        org.web3j.abi.datatypes.Address.class,
                        org.web3j.abi.Utils.typeMap(addedTokens, org.web3j.abi.datatypes.Address.class)), 
                new org.web3j.abi.datatypes.DynamicArray<org.web3j.abi.datatypes.generated.Uint8>(
                        org.web3j.abi.datatypes.generated.Uint8.class,
                        org.web3j.abi.Utils.typeMap(addedTokenExponents, org.web3j.abi.datatypes.generated.Uint8.class))), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    @Deprecated
    public static StandardBridgeContract load(String contractAddress, Web3j web3j,
            Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        return new StandardBridgeContract(contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    @Deprecated
    public static StandardBridgeContract load(String contractAddress, Web3j web3j,
            TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return new StandardBridgeContract(contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    public static StandardBridgeContract load(String contractAddress, Web3j web3j,
            Credentials credentials, ContractGasProvider contractGasProvider) {
        return new StandardBridgeContract(contractAddress, web3j, credentials, contractGasProvider);
    }

    public static StandardBridgeContract load(String contractAddress, Web3j web3j,
            TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return new StandardBridgeContract(contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public static RemoteCall<StandardBridgeContract> deploy(Web3j web3j, Credentials credentials,
            ContractGasProvider contractGasProvider) {
        return deployRemoteCall(StandardBridgeContract.class, web3j, credentials, contractGasProvider, getDeploymentBinary(), "");
    }

    @Deprecated
    public static RemoteCall<StandardBridgeContract> deploy(Web3j web3j, Credentials credentials,
            BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(StandardBridgeContract.class, web3j, credentials, gasPrice, gasLimit, getDeploymentBinary(), "");
    }

    public static RemoteCall<StandardBridgeContract> deploy(Web3j web3j,
            TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(StandardBridgeContract.class, web3j, transactionManager, contractGasProvider, getDeploymentBinary(), "");
    }

    @Deprecated
    public static RemoteCall<StandardBridgeContract> deploy(Web3j web3j,
            TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(StandardBridgeContract.class, web3j, transactionManager, gasPrice, gasLimit, getDeploymentBinary(), "");
    }

    private static String getDeploymentBinary() {
        if (librariesLinkedBinary != null) {
            return librariesLinkedBinary;
        } else {
            return BINARY;
        }
    }

    public static class ERC20BridgeFinalizedEventResponse extends BaseEventResponse {
        public String localToken;

        public String elTo;

        public java.lang.Long clAmount;
    }

    public static class ERC20BridgeInitiatedEventResponse extends BaseEventResponse {
        public String localToken;

        public byte[] clTo;

        public java.lang.Long clAmount;
    }

    public static class RegistryUpdatedEventResponse extends BaseEventResponse {
        public List<String> addedTokens;

        public List<BigInteger> addedTokenExponents;

        public List<String> removedTokens;
    }
}
