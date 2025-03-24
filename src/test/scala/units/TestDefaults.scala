package units

import units.eth.{EthAddress, Gwei}

import scala.util.Random

trait TestDefaults {
  protected val ElMinerDefaultReward  = Gwei.ofRawGwei(2_000_000_000L)
  protected val NativeBridgeAddress   = EthAddress.unsafeFrom("0x0000000000000000000000000000000000006a7e")
  protected val StandardBridgeAddress = EthAddress.unsafeFrom("0xa50a51c09a5c451C52BB714527E1974b686D8e77")

  protected val WWavesAddress = EthAddress.unsafeFrom("0x9a3DBCa554e9f6b9257aAa24010DA8377C57c17e")

  protected val TErc20Address  = EthAddress.unsafeFrom("0x9B8397f1B0FEcD3a1a40CdD5E8221Fa461898517")
  protected val TErc20Decimals = 18

  protected def mkRandomEthAddress(): EthAddress = EthAddress.unsafeFrom(Random.nextBytes(20))
}
