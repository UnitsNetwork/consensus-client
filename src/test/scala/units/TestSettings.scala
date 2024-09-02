package units

import com.typesafe.config.ConfigFactory
import com.wavesplatform.account.SeedKeyPair
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.test.{DomainPresets, NumericExt}
import com.wavesplatform.transaction.utils.EthConverters.EthereumAddressExt
import units.TestSettings.*
import units.eth.EthAddress

case class TestSettings(
    wavesSettings: WavesSettings = Waves.Default,
    initialMiners: List[ElMinerSettings] = Nil,
    additionalBalances: List[AddrWithBalance] = Nil
) {
  def finalAdditionalBalances: List[AddrWithBalance] = additionalBalances ++
    initialMiners.collect { case x if !additionalBalances.exists(_.address == x.address) => AddrWithBalance(x.address, x.wavesBalance) }

  def walletSeed: Array[Byte] = wavesSettings.walletSettings.seed.getOrElse(throw new RuntimeException("No wallet seed")).arr

  def withEnabledElMining: TestSettings = copy(wavesSettings = Waves.WithMining)
}

object TestSettings {
  val Default = TestSettings()

  private object Waves {
    val Default    = DomainPresets.TransactionStateSnapshot
    val WithMining = Default.copy(config = ConfigFactory.parseString("waves.l2.mining-enable = true").withFallback(Default.config))
  }
}

case class ElMinerSettings(
    account: SeedKeyPair,
    wavesBalance: Long = 20_100.waves,
    stakingBalance: Long = 50_000_000L
) {
  val address         = account.toAddress
  val elRewardAddress = EthAddress.unsafeFrom(address.toEthAddress)
}
