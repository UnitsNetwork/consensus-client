package units

import units.eth.{EthAddress, Gwei}

trait TestDefaults {
  protected val elMinerDefaultReward  = Gwei.ofRawGwei(2_000_000_000L)
  protected val nativeBridgeAddress   = EthAddress.unsafeFrom("0x0000000000000000000000000000000000006a7e")
  protected val standardBridgeAddress = EthAddress.unsafeFrom("0x9a3dbca554e9f6b9257aaa24010da8377c57c17e")
}
