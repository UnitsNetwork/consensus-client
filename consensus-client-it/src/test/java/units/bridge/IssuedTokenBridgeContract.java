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
import org.web3j.abi.datatypes.generated.Uint40;
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
    public static final String BINARY = "6080604052348015600e575f80fd5b506110fe8061001c5f395ff3fe608060405234801561000f575f80fd5b5060043610610085575f3560e01c806340c10f191161005857806340c10f19146101195780636722fb501461012c5780637157405a14610168578063fb3db41a14610171575f80fd5b8063044a2ef91461008957806322173e541461009e57806327e235e3146100b157806339dd5d1b146100e3575b5f80fd5b61009c6100973660046109ed565b610184565b005b61009c6100ac366004610a34565b61033e565b6100d06100bf366004610a77565b5f6020819052908152604090205481565b6040519081526020015b60405180910390f35b6101066100f1366004610a97565b60016020525f908152604090205461ffff1681565b60405161ffff90911681526020016100da565b61009c610127366004610aae565b61065d565b61015261013a366004610a77565b60026020525f908152604090205464ffffffffff1681565b60405164ffffffffff90911681526020016100da565b61010661040081565b61009c61017f366004610b1e565b61068d565b5f8260070b136101ef5760405162461bcd60e51b815260206004820152602b60248201527f526563656976652076616c7565206d7573742062652067726561746572206f7260448201526a020657175616c20746f20360ac1b60648201526084015b60405180910390fd5b305f9081526002602052604090205464ffffffffff168061024c5760405162461bcd60e51b8152602060048201526017602482015276151bdad95b881a5cc81b9bdd081c9959da5cdd195c9959604a1b60448201526064016101e6565b5f61026264ffffffffff8316600786900b610b9e565b90505f61027e64ffffffffff8416677fffffffffffffff610b9e565b9050808211156102df5760405162461bcd60e51b815260206004820152602660248201527f416d6f756e742065786365656473206d6178696d756d20616c6c6f7761626c656044820152652076616c756560d01b60648201526084016101e6565b6102e9868361065d565b604080516001600160a01b038881168252600788900b602083015286168183015290517f79ba61b7b220c73204f9deefe02364b84339a67c836feb371b0b7c9860084d5e9181900360600190a1505050505050565b6001600160a01b0381165f9081526002602052604090205464ffffffffff16806103a45760405162461bcd60e51b8152602060048201526017602482015276151bdad95b881a5cc81b9bdd081c9959da5cdd195c9959604a1b60448201526064016101e6565b5f6103b0826001610bbb565b64ffffffffff90811691505f906103d1908416677fffffffffffffff610b9e565b9050818510156103e086610885565b6103e984610885565b6040516020016103fa929190610bf9565b604051602081830303815290604052906104275760405162461bcd60e51b81526004016101e69190610c51565b508085111561043586610885565b61043e83610885565b60405160200161044f929190610c86565b6040516020818303038152906040529061047c5760405162461bcd60e51b81526004016101e69190610c51565b50335f9081526020819052604090205485811161049882610885565b6040516020016104a89190610cd5565b604051602081830303815290604052906104d55760405162461bcd60e51b81526004016101e69190610c51565b505f6104e864ffffffffff861688610d20565b9050866104fc64ffffffffff871683610b9e565b1461050688610885565b6105168764ffffffffff16610885565b604051602001610527929190610d3f565b604051602081830303815290604052906105545760405162461bcd60e51b81526004016101e69190610c51565b50435f8181526001602052604090205461ffff166104009081119061057890610885565b6040516020016105889190610d8e565b604051602081830303815290604052906105b55760405162461bcd60e51b81526004016101e69190610c51565b505f818152600160205260408120805461ffff16916105d383610dfc565b91906101000a81548161ffff021916908361ffff160217905550506105f833896109ab565b604080516bffffffffffffffffffffffff198b168152600784900b60208201526001600160a01b0389168183015290517fdc193e59112938988cb57c4abdeb47ac3889408ba6f39d7061bee9476907bedc9181900360600190a1505050505050505050565b6001600160a01b0382165f9081526020819052604081208054839290610684908490610e1c565b90915550505050565b8281146106f85760405162461bcd60e51b815260206004820152603360248201527f446966666572656e742073697a6573206f662061646465642061737365747320604482015272616e64207468656972206578706f6e656e747360681b60648201526084016101e6565b5f5b83811015610815575f83838381811061071557610715610e2f565b905060200201602081019061072a9190610e53565b9050600a8160ff1611156107408260ff16610885565b6040516020016107509190610e6c565b6040516020818303038152906040529061077d5760405162461bcd60e51b81526004016101e69190610c51565b5083838381811061079057610790610e2f565b90506020020160208101906107a59190610e53565b6107b090600a610f80565b60025f8888868181106107c5576107c5610e2f565b90506020020160208101906107da9190610a77565b6001600160a01b0316815260208101919091526040015f20805464ffffffffff191664ffffffffff92909216919091179055506001016106fa565b507f6f1865b1fb41fed526e00bdd475fbb6a25987b5a14e360a4357f68b573b736e7848484845f604051908082528060200260200182016040528015610865578160200160208202803683370190505b50604051610877959493929190610fe5565b60405180910390a150505050565b6060815f036108ab5750506040805180820190915260018152600360fc1b602082015290565b815f5b81156108d457806108be81611084565b91506108cd9050600a83610d20565b91506108ae565b5f8167ffffffffffffffff8111156108ee576108ee610f8e565b6040519080825280601f01601f191660200182016040528015610918576020820181803683370190505b509050815b85156109a25761092e60018261109c565b90505f61093c600a88610d20565b61094790600a610b9e565b610951908861109c565b61095c9060306110af565b90505f8160f81b90508084848151811061097857610978610e2f565b60200101906001600160f81b03191690815f1a905350610999600a89610d20565b9750505061091d565b50949350505050565b6001600160a01b0382165f908152602081905260408120805483929061068490849061109c565b80356001600160a01b03811681146109e8575f80fd5b919050565b5f805f606084860312156109ff575f80fd5b610a08846109d2565b925060208401358060070b8114610a1d575f80fd5b9150610a2b604085016109d2565b90509250925092565b5f805f60608486031215610a46575f80fd5b83356bffffffffffffffffffffffff1981168114610a62575f80fd5b925060208401359150610a2b604085016109d2565b5f60208284031215610a87575f80fd5b610a90826109d2565b9392505050565b5f60208284031215610aa7575f80fd5b5035919050565b5f8060408385031215610abf575f80fd5b610ac8836109d2565b946020939093013593505050565b5f8083601f840112610ae6575f80fd5b50813567ffffffffffffffff811115610afd575f80fd5b6020830191508360208260051b8501011115610b17575f80fd5b9250929050565b5f805f8060408587031215610b31575f80fd5b843567ffffffffffffffff811115610b47575f80fd5b610b5387828801610ad6565b909550935050602085013567ffffffffffffffff811115610b72575f80fd5b610b7e87828801610ad6565b95989497509550505050565b634e487b7160e01b5f52601160045260245ffd5b8082028115828204841417610bb557610bb5610b8a565b92915050565b64ffffffffff8181168382160290811690818114610bdb57610bdb610b8a565b5092915050565b5f81518060208401855e5f93019283525090919050565b6a029b2b73a103b30b63ab2960ad1b81525f610c18600b830185610be2565b7f206d7573742062652067726561746572206f7220657175616c20746f200000008152610c48601d820185610be2565b95945050505050565b602081525f82518060208401528060208501604085015e5f604082850101526040601f19601f83011684010191505092915050565b6a029b2b73a103b30b63ab2960ad1b81525f610ca5600b830185610be2565b7f206d757374206265206c657373206f7220657175616c20746f200000000000008152610c48601a820185610be2565b7f496e73756666696369656e742066756e64732c206f6e6c79200000000000000081525f610d066019830184610be2565b6920617661696c61626c6560b01b8152600a019392505050565b5f82610d3a57634e487b7160e01b5f52601260045260245ffd5b500490565b6a029b2b73a103b30b63ab2960ad1b81525f610d5e600b830185610be2565b7f206d7573742062652061206d756c7469706c65206f66200000000000000000008152610c486017820185610be2565b7f4d6178207472616e7366657273206c696d6974206f662000000000000000000081525f610dbf6017830184610be2565b7f207265616368656420696e207468697320626c6f636b2e20547279206167616981526637103630ba32b960c91b60208201526027019392505050565b5f61ffff821661ffff8103610e1357610e13610b8a565b60010192915050565b80820180821115610bb557610bb5610b8a565b634e487b7160e01b5f52603260045260245ffd5b803560ff811681146109e8575f80fd5b5f60208284031215610e63575f80fd5b610a9082610e43565b7f496e76616c6964206173736574206578706f6e656e743a20000000000000000081525f610a906018830184610be2565b6001815b6001841115610ed857808504811115610ebc57610ebc610b8a565b6001841615610eca57908102905b60019390931c928002610ea1565b935093915050565b5f82610eee57506001610bb5565b81610efa57505f610bb5565b8160018114610f105760028114610f1a57610f36565b6001915050610bb5565b60ff841115610f2b57610f2b610b8a565b50506001821b610bb5565b5060208310610133831016604e8410600b8410161715610f59575081810a610bb5565b610f655f198484610e9d565b805f1904821115610f7857610f78610b8a565b029392505050565b5f610a9060ff841683610ee0565b634e487b7160e01b5f52604160045260245ffd5b5f8151808452602084019350602083015f5b82811015610fdb5781516001600160a01b0316865260209586019590910190600101610fb4565b5093949350505050565b606080825281018590525f8660808301825b88811015611025576001600160a01b03611010846109d2565b16825260209283019290910190600101610ff7565b50838103602080860191909152868252019050855f5b868110156110645760ff61104e83610e43565b168352602092830192919091019060010161103b565b505082810360408401526110788185610fa2565b98975050505050505050565b5f6001820161109557611095610b8a565b5060010190565b81810381811115610bb557610bb5610b8a565b60ff8181168382160190811115610bb557610bb5610b8a56fea2646970667358221220f8fc1c3db0e2edd145e4ee545d9c4a7cd4c80be1aa54417ff57b28eb3d9d38f564736f6c634300081a0033";

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

    public RemoteFunctionCall<Uint40> call_tokensRatio(String param0) {
        final Function function = new Function(FUNC_TOKENSRATIO, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint40>() {}));
        return executeRemoteCallSingleValueReturn(function, Uint40.class);
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
