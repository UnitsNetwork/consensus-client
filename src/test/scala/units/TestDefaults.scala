package units

import units.eth.{EthAddress, Gwei}

import scala.util.Random

trait TestDefaults {
  protected val ElMinerDefaultReward  = Gwei.ofRawGwei(2_000_000_000L)
  protected val NativeBridgeAddress   = EthAddress.unsafeFrom("0x0000000000000000000000000000000000006a7e")
  protected val StandardBridgeAddress = EthAddress.unsafeFrom("0x9a3dbca554e9f6b9257aaa24010da8377c57c17e")
  protected val WWavesContractAddress = EthAddress.unsafeFrom("0x2e1f232a9439c3d459fceca0beef13acc8259dd8")
  protected val TErc20Address         = EthAddress.unsafeFrom("0x9b8397f1b0fecd3a1a40cdd5e8221fa461898517")

  protected def mkRandomEthAddress(): EthAddress = EthAddress.unsafeFrom(Random.nextBytes(20))
}
