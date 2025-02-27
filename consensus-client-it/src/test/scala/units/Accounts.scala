package units

import com.google.common.primitives.{Bytes, Ints}
import com.wavesplatform.account.{KeyPair, SeedKeyPair}
import com.wavesplatform.crypto
import org.web3j.crypto.Credentials
import units.eth.EthAddress

import java.nio.charset.StandardCharsets

trait Accounts {
  val chainRegistryAccount: KeyPair = mkKeyPair("devnet registry", 0)
  val chainContractAccount: KeyPair = mkKeyPair("devnet cc", 0)

  val miner11Account       = mkKeyPair("devnet-1", 0)
  val miner11RewardAddress = EthAddress.unsafeFrom("0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f9")

  val miner12Account       = mkKeyPair("devnet-1", 1)
  val miner12RewardAddress = EthAddress.unsafeFrom("0x7dbcf9c6c3583b76669100f9be3caf6d722bc9f0")

  val miner21Account       = mkKeyPair("devnet-2", 0)
  val miner21RewardAddress = EthAddress.unsafeFrom("0xcf0b9e13fdd593f4ca26d36afcaa44dd3fdccbed")

  val clRichAccount1 = mkKeyPair("devnet rich", 0)
  val clRichAccount2 = mkKeyPair("devnet rich", 1)

  val elRichAccount1PrivateKey = "8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63"
  val elRichAccount1 = Credentials.create(elRichAccount1PrivateKey)
  val elRichAddress1           = EthAddress.unsafeFrom(elRichAccount1.getAddress)

  val elRichAccount2 = Credentials.create("ae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f")
  val elRichAddress2 = EthAddress.unsafeFrom(elRichAccount2.getAddress)

  protected def mkKeyPair(seed: String, nonce: Int): SeedKeyPair =
    SeedKeyPair(crypto.secureHash(Bytes.concat(Ints.toByteArray(nonce), seed.getBytes(StandardCharsets.UTF_8))))
}
