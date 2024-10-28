package units.docker

import com.github.dockerjava.api.command.CreateNetworkCmd
import com.github.dockerjava.api.model.Network.Ipam
import com.google.common.primitives.Ints.toByteArray
import org.testcontainers.containers.Network

import java.net.InetAddress
import scala.util.Random

object Networks {
  // A random network in 10.x.x.x range
  val networkSeed = Random.nextInt(0x100000) << 4 | 0x0a000000

  // 10.x.x.x/28 network will accommodate up to 13 nodes
  val networkPrefix = s"${InetAddress.getByAddress(toByteArray(networkSeed)).getHostAddress}/28"

  val network = Network
    .builder()
    .driver("bridge")
    .enableIpv6(false)
    .createNetworkCmdModifier((n: CreateNetworkCmd) =>
      n.withIpam(new Ipam().withConfig(new Ipam.Config().withSubnet(networkPrefix).withIpRange(networkPrefix)))
    )
    .build()

  def ipForNode(nodeId: Int): String = InetAddress.getByAddress(toByteArray(nodeId & 0xf | networkSeed)).getHostAddress
}
