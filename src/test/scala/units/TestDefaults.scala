package units

import units.eth.{EthAddress, Gwei}

trait TestDefaults {
  protected val elMinerDefaultReward    = Gwei.ofRawGwei(2_000_000_000L)
  protected val elNativeBridgeAddress   = EthAddress.unsafeFrom("0x0000000000000000000000000000000000006a7e")
  protected val elStandardBridgeAddress = EthAddress.unsafeFrom("0x0000000000000000000000000000000057d06a7e")
}
