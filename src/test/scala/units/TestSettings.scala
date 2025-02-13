package units

import com.typesafe.config.ConfigFactory
import com.wavesplatform.account.{Address, KeyPair, PrivateKey, SeedKeyPair}
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.test.{DomainPresets, NumericExt}
import com.wavesplatform.transaction.utils.EthConverters.EthereumAddressExt
import units.TestSettings.*
import units.eth.EthAddress

case class TestSettings(
    wavesSettings: WavesSettings = Waves.Default,
    initialMiners: List[ElMinerSettings] = Nil,
    additionalBalances: List[AddrWithBalance] = Nil,
    daoRewardAccount: Option[KeyPair] = None,
    daoRewardAmount: Long = 0
) {
  def finalAdditionalBalances: List[AddrWithBalance] = additionalBalances ++
    initialMiners.collect { case x if !additionalBalances.exists(_.address == x.address) => AddrWithBalance(x.address, x.wavesBalance) }

  def walletSeed: Array[Byte] = wavesSettings.walletSettings.seed.getOrElse(throw new RuntimeException("No wallet seed")).arr

  def withEnabledElMining: TestSettings = copy(wavesSettings =
    wavesSettings.copy(config = ConfigFactory.parseString("units.defaults.mining-enable = true").withFallback(wavesSettings.config))
  )

  def withChainRegistry(address: Address): TestSettings = copy(wavesSettings = Waves.withChainRegistry(wavesSettings, Some(address)))

  def withPrivateKeys(privateKeys: Seq[PrivateKey]): TestSettings = {
    val configString = s"units.defaults.private-keys = [${privateKeys.mkString(", ")}]"
    copy(wavesSettings = wavesSettings.copy(config = ConfigFactory.parseString(configString).withFallback(wavesSettings.config)))
  }
}

object TestSettings {
  private object Waves {
    val Default = withChainRegistry(DomainPresets.TransactionStateSnapshot, None)

    def withChainRegistry(settings: WavesSettings, address: Option[Address]): WavesSettings =
      settings.copy(blockchainSettings =
        settings.blockchainSettings.copy(functionalitySettings =
          settings.blockchainSettings.functionalitySettings.copy(unitsRegistryAddress = address.map(_.toString))
        )
      )
  }
}

case class ElMinerSettings(
    account: SeedKeyPair,
    wavesBalance: Long = 20_100.waves
) {
  val address         = account.toAddress
  val elRewardAddress = EthAddress.unsafeFrom(address.toEthAddress)
}
