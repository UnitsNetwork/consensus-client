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
    public static final String BINARY = "6080604052348015600e575f80fd5b5061111e8061001c5f395ff3fe608060405234801561000f575f80fd5b5060043610610085575f3560e01c806339dd5d1b1161005857806339dd5d1b146100f65780633f001f371461012c57806340c10f191461016c5780637157405a1461017f575f80fd5b8063044a2ef9146100895780631744f1671461009e57806322173e54146100b157806327e235e3146100c4575b5f80fd5b61009c610097366004610a0e565b610188565b005b61009c6100ac366004610a9c565b610351565b61009c6100bf366004610b06565b61054e565b6100e36100d2366004610b49565b5f6020819052908152604090205481565b6040519081526020015b60405180910390f35b610119610104366004610b69565b60016020525f908152604090205461ffff1681565b60405161ffff90911681526020016100ed565b61015461013a366004610b49565b60026020525f90815260409020546001600160401b031681565b6040516001600160401b0390911681526020016100ed565b61009c61017a366004610b80565b610877565b61011961040081565b5f8260070b136101f35760405162461bcd60e51b815260206004820152602b60248201527f526563656976652076616c7565206d7573742062652067726561746572206f7260448201526a020657175616c20746f20360ac1b60648201526084015b60405180910390fd5b6001600160a01b0381165f908152600260205260409020546001600160401b03168061025b5760405162461bcd60e51b8152602060048201526017602482015276105cdcd95d081a5cc81b9bdd081c9959da5cdd195c9959604a1b60448201526064016101ea565b5f6102736001600160401b038316600786900b610bbc565b90505f6102916001600160401b038416677fffffffffffffff610bbc565b9050808211156102f25760405162461bcd60e51b815260206004820152602660248201527f416d6f756e742065786365656473206d6178696d756d20616c6c6f7761626c656044820152652076616c756560d01b60648201526084016101ea565b6102fc8683610877565b604080516001600160a01b038881168252600788900b602083015286168183015290517f79ba61b7b220c73204f9deefe02364b84339a67c836feb371b0b7c9860084d5e9181900360600190a1505050505050565b8281146103bc5760405162461bcd60e51b815260206004820152603360248201527f446966666572656e742073697a6573206f662061646465642061737365747320604482015272616e64207468656972206578706f6e656e747360681b60648201526084016101ea565b5f5b838110156104de575f8383838181106103d9576103d9610bd9565b90506020020160208101906103ee9190610bfd565b9050600a8160ff1611156104048260ff166108a7565b6040516020016104149190610c2d565b604051602081830303815290604052906104415760405162461bcd60e51b81526004016101ea9190610c5e565b5083838381811061045457610454610bd9565b90506020020160208101906104699190610bfd565b61047490600a610d76565b60025f88888681811061048957610489610bd9565b905060200201602081019061049e9190610b49565b6001600160a01b0316815260208101919091526040015f20805467ffffffffffffffff19166001600160401b0392909216919091179055506001016103be565b507f6f1865b1fb41fed526e00bdd475fbb6a25987b5a14e360a4357f68b573b736e7848484845f60405190808252806020026020018201604052801561052e578160200160208202803683370190505b50604051610540959493929190610ddb565b60405180910390a150505050565b6001600160a01b0381165f908152600260205260409020546001600160401b0316806105b65760405162461bcd60e51b8152602060048201526017602482015276105cdcd95d081a5cc81b9bdd081c9959da5cdd195c9959604a1b60448201526064016101ea565b5f6105c2826001610e7a565b6001600160401b0390811691505f906105e5908416677fffffffffffffff610bbc565b9050818510156105f4866108a7565b6105fd846108a7565b60405160200161060e929190610ea3565b6040516020818303038152906040529061063b5760405162461bcd60e51b81526004016101ea9190610c5e565b5080851115610649866108a7565b610652836108a7565b604051602001610663929190610efb565b604051602081830303815290604052906106905760405162461bcd60e51b81526004016101ea9190610c5e565b50335f908152602081905260409020548581116106ac826108a7565b6040516020016106bc9190610f4a565b604051602081830303815290604052906106e95760405162461bcd60e51b81526004016101ea9190610c5e565b505f6106fe6001600160401b03861688610f95565b9050866107146001600160401b03871683610bbc565b1461071e886108a7565b610730876001600160401b03166108a7565b604051602001610741929190610fb4565b6040516020818303038152906040529061076e5760405162461bcd60e51b81526004016101ea9190610c5e565b50435f8181526001602052604090205461ffff1661040090811190610792906108a7565b6040516020016107a29190611003565b604051602081830303815290604052906107cf5760405162461bcd60e51b81526004016101ea9190610c5e565b505f818152600160205260408120805461ffff16916107ed83611071565b91906101000a81548161ffff021916908361ffff1602179055505061081233896109cc565b604080516bffffffffffffffffffffffff198b168152600784900b60208201526001600160a01b0389168183015290517fdc193e59112938988cb57c4abdeb47ac3889408ba6f39d7061bee9476907bedc9181900360600190a1505050505050505050565b6001600160a01b0382165f908152602081905260408120805483929061089e908490611091565b90915550505050565b6060815f036108cd5750506040805180820190915260018152600360fc1b602082015290565b815f5b81156108f657806108e0816110a4565b91506108ef9050600a83610f95565b91506108d0565b5f816001600160401b0381111561090f5761090f610d84565b6040519080825280601f01601f191660200182016040528015610939576020820181803683370190505b509050815b85156109c35761094f6001826110bc565b90505f61095d600a88610f95565b61096890600a610bbc565b61097290886110bc565b61097d9060306110cf565b90505f8160f81b90508084848151811061099957610999610bd9565b60200101906001600160f81b03191690815f1a9053506109ba600a89610f95565b9750505061093e565b50949350505050565b6001600160a01b0382165f908152602081905260408120805483929061089e9084906110bc565b80356001600160a01b0381168114610a09575f80fd5b919050565b5f805f60608486031215610a20575f80fd5b610a29846109f3565b925060208401358060070b8114610a3e575f80fd5b9150610a4c604085016109f3565b90509250925092565b5f8083601f840112610a65575f80fd5b5081356001600160401b03811115610a7b575f80fd5b6020830191508360208260051b8501011115610a95575f80fd5b9250929050565b5f805f8060408587031215610aaf575f80fd5b84356001600160401b03811115610ac4575f80fd5b610ad087828801610a55565b90955093505060208501356001600160401b03811115610aee575f80fd5b610afa87828801610a55565b95989497509550505050565b5f805f60608486031215610b18575f80fd5b83356bffffffffffffffffffffffff1981168114610b34575f80fd5b925060208401359150610a4c604085016109f3565b5f60208284031215610b59575f80fd5b610b62826109f3565b9392505050565b5f60208284031215610b79575f80fd5b5035919050565b5f8060408385031215610b91575f80fd5b610b9a836109f3565b946020939093013593505050565b634e487b7160e01b5f52601160045260245ffd5b8082028115828204841417610bd357610bd3610ba8565b92915050565b634e487b7160e01b5f52603260045260245ffd5b803560ff81168114610a09575f80fd5b5f60208284031215610c0d575f80fd5b610b6282610bed565b5f81518060208401855e5f93019283525090919050565b7f496e76616c6964206173736574206578706f6e656e743a20000000000000000081525f610b626018830184610c16565b602081525f82518060208401528060208501604085015e5f604082850101526040601f19601f83011684010191505092915050565b6001815b6001841115610cce57808504811115610cb257610cb2610ba8565b6001841615610cc057908102905b60019390931c928002610c97565b935093915050565b5f82610ce457506001610bd3565b81610cf057505f610bd3565b8160018114610d065760028114610d1057610d2c565b6001915050610bd3565b60ff841115610d2157610d21610ba8565b50506001821b610bd3565b5060208310610133831016604e8410600b8410161715610d4f575081810a610bd3565b610d5b5f198484610c93565b805f1904821115610d6e57610d6e610ba8565b029392505050565b5f610b6260ff841683610cd6565b634e487b7160e01b5f52604160045260245ffd5b5f8151808452602084019350602083015f5b82811015610dd15781516001600160a01b0316865260209586019590910190600101610daa565b5093949350505050565b606080825281018590525f8660808301825b88811015610e1b576001600160a01b03610e06846109f3565b16825260209283019290910190600101610ded565b50838103602080860191909152868252019050855f5b86811015610e5a5760ff610e4483610bed565b1683526020928301929190910190600101610e31565b50508281036040840152610e6e8185610d98565b98975050505050505050565b6001600160401b038181168382160290811690818114610e9c57610e9c610ba8565b5092915050565b6a029b2b73a103b30b63ab2960ad1b81525f610ec2600b830185610c16565b7f206d7573742062652067726561746572206f7220657175616c20746f200000008152610ef2601d820185610c16565b95945050505050565b6a029b2b73a103b30b63ab2960ad1b81525f610f1a600b830185610c16565b7f206d757374206265206c657373206f7220657175616c20746f200000000000008152610ef2601a820185610c16565b7f496e73756666696369656e742066756e64732c206f6e6c79200000000000000081525f610f7b6019830184610c16565b6920617661696c61626c6560b01b8152600a019392505050565b5f82610faf57634e487b7160e01b5f52601260045260245ffd5b500490565b6a029b2b73a103b30b63ab2960ad1b81525f610fd3600b830185610c16565b7f206d7573742062652061206d756c7469706c65206f66200000000000000000008152610ef26017820185610c16565b7f4d6178207472616e7366657273206c696d6974206f662000000000000000000081525f6110346017830184610c16565b7f207265616368656420696e207468697320626c6f636b2e20547279206167616981526637103630ba32b960c91b60208201526027019392505050565b5f61ffff821661ffff810361108857611088610ba8565b60010192915050565b80820180821115610bd357610bd3610ba8565b5f600182016110b5576110b5610ba8565b5060010190565b81810381811115610bd357610bd3610ba8565b60ff8181168382160190811115610bd357610bd3610ba856fea26469706673582212205d6ce41c2a42674ce70c972501c82db2f12f0654274e62cdb3f46c4cd8a87ce264736f6c634300081a0033";

    private static String librariesLinkedBinary;

    public static final String FUNC_MAX_TRANSFERS_IN_BLOCK = "MAX_TRANSFERS_IN_BLOCK";

    public static final String FUNC_ASSETRATIOS = "assetRatios";

    public static final String FUNC_BALANCES = "balances";

    public static final String FUNC_BRIDGEERC20 = "bridgeERC20";

    public static final String FUNC_FINALIZEBRIDGEERC20 = "finalizeBridgeERC20";

    public static final String FUNC_MINT = "mint";

    public static final String FUNC_TRANSFERSPERBLOCK = "transfersPerBlock";

    public static final String FUNC_UPDATEASSETREGISTRY = "updateAssetRegistry";

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

    public RemoteFunctionCall<BigInteger> call_assetRatios(String param0) {
        final Function function = new Function(FUNC_ASSETRATIOS, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint64>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<TransactionReceipt> send_assetRatios(String param0) {
        final Function function = new Function(
                FUNC_ASSETRATIOS, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
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

    public RemoteFunctionCall<TransactionReceipt> send_updateAssetRegistry(List<String> addedAssets,
            List<BigInteger> addedAssetExponents) {
        final Function function = new Function(
                FUNC_UPDATEASSETREGISTRY, 
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
