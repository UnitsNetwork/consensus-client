package units.client.contract

case class ChainInfo(id: Long, isMain: Boolean, firstBlock: ContractBlock, lastBlock: ContractBlock)
